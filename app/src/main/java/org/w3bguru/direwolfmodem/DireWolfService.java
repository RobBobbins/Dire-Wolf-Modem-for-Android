/*
 * Dire Wolf Modem for Android — runs the Dire Wolf packet modem and TNC.
 * Copyright (C) 2026. GPL-2.0-or-later; see assets/LICENSE.txt.
 */
package org.w3bguru.direwolfmodem;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service that runs the Dire Wolf program (libdirewolf.so) as a child
 * process. Dire Wolf offers KISS and AGW on 127.0.0.1 only; a packet program on
 * this phone (FieldMail) connects to the KISS port and runs its own AX.25.
 */
public final class DireWolfService extends Service {
    public static final String ACTION_STOP = "org.w3bguru.direwolfmodem.STOP";
    public static final int KISS_PORT = 8101;
    public static final int AGW_PORT = 8100;

    private static final String CHANNEL_ID = "direwolf";
    private static final int NOTIFICATION_ID = 1;
    private static final int LOG_LINES_KEPT = 200;

    // Read by MainActivity (same process).
    private static volatile String status = "Stopped";
    private static final ArrayDeque<String> logLines = new ArrayDeque<>();

    private Process process;
    private Thread watcher;
    private volatile boolean stopping;

    public static String status() {
        return status;
    }

    public static List<String> lastLogLines(int n) {
        synchronized (logLines) {
            List<String> all = new ArrayList<>(logLines);
            return all.subList(Math.max(0, all.size() - n), all.size());
        }
    }

    private static void addLogLine(String line) {
        synchronized (logLines) {
            logLines.addLast(line);
            while (logLines.size() > LOG_LINES_KEPT) logLines.removeFirst();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopDireWolf();
            return START_NOT_STICKY;
        }
        startInForeground();
        if (process == null) startDireWolf();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopDireWolf();
        super.onDestroy();
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Dire Wolf modem", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Dire Wolf modem running")
                .setContentText("KISS " + KISS_PORT + " / AGW " + AGW_PORT + " on this phone")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    /** Dire Wolf settings: phone's default microphone and speaker, 1200 baud, ports on this phone only. */
    static String config() {
        return "ADEVICE default\n"
                + "ARATE 48000\n"
                + "ACHANNELS 1\n"
                + "CHANNEL 0\n"
                + "MYCALL NOCALL\n"
                + "MODEM 1200\n"
                + "AGWPORT " + AGW_PORT + "\n"
                // KISSPORT 0 removes Dire Wolf's default KISS port 8001, so only KISS_PORT is open.
                + "KISSPORT 0\n"
                + "KISSPORT " + KISS_PORT + "\n";
    }

    private void startDireWolf() {
        File exe = new File(getApplicationInfo().nativeLibraryDir, "libdirewolf.so");
        File conf = new File(getFilesDir(), "direwolf.conf");
        File console = new File(getFilesDir(), "direwolf-console.txt");
        //noinspection ResultOfMethodCallIgnored
        console.delete();
        synchronized (logLines) {
            logLines.clear();
        }
        stopping = false;
        try {
            try (FileOutputStream out = new FileOutputStream(conf)) {
                out.write(config().getBytes(StandardCharsets.US_ASCII));
            }
            // -t 0: no colour codes in the output.
            ProcessBuilder pb = new ProcessBuilder(exe.getAbsolutePath(), "-t", "0", "-c", conf.getAbsolutePath());
            pb.directory(getFilesDir());
            pb.redirectErrorStream(true);
            pb.redirectOutput(console);
            process = pb.start();
            status = "Starting…";
        } catch (Exception e) {
            status = "Could not start Dire Wolf: " + e.getMessage();
            addLogLine(status);
            process = null;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        final Process p = process;
        watcher = new Thread(() -> watch(p, console), "direwolf-watch");
        watcher.start();
    }

    /** Follows Dire Wolf's screen output (one line at a time) and notices when the process ends. */
    private void watch(Process p, File console) {
        long offset = 0;
        StringBuilder partial = new StringBuilder();
        while (true) {
            boolean alive = p.isAlive();
            if (console.exists()) {
                try (RandomAccessFile f = new RandomAccessFile(console, "r")) {
                    if (f.length() > offset) {
                        f.seek(offset);
                        byte[] buf = new byte[(int) Math.min(f.length() - offset, 65536)];
                        f.readFully(buf);
                        offset += buf.length;
                        partial.append(new String(buf, StandardCharsets.UTF_8));
                        int nl;
                        while ((nl = partial.indexOf("\n")) >= 0) {
                            String line = partial.substring(0, nl).trim();
                            partial.delete(0, nl + 1);
                            if (!line.isEmpty()) handleLine(line);
                        }
                    }
                } catch (Exception ignored) {
                    // File briefly unavailable; try again next pass.
                }
            }
            if (!alive) break;
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                break;
            }
        }
        int code = p.isAlive() ? -1 : p.exitValue();
        status = stopping ? "Stopped" : "Stopped — Dire Wolf exited (code " + code + ")";
        addLogLine(status);
        process = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void handleLine(String line) {
        addLogLine(line);
        if (line.startsWith("Ready to accept KISS TCP client")) {
            if (!status.startsWith("Running — program attached")) status = "Running — waiting for a program on port " + KISS_PORT;
        } else if (line.startsWith("Attached to KISS TCP client")) {
            status = "Running — program attached";
        } else if (line.contains("has gone away")) {
            status = "Running — waiting for a program on port " + KISS_PORT;
        } else if (line.startsWith("Could not open audio device")) {
            status = "Sound problem — see log";
        }
    }

    private void stopDireWolf() {
        final Process p = process;
        if (p == null) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        stopping = true;
        status = "Stopping…";
        new Thread(() -> {
            p.destroy();
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException ignored) {
                p.destroyForcibly();
            }
        }, "direwolf-stop").start();
    }
}
