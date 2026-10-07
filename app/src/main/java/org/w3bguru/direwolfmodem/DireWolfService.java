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
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
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
    /** Ports of the run in progress (from the settings at Start). */
    private static volatile int kissPort, agwPort;

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
        ModemSettings s = ModemSettings.load(this);
        kissPort = s.kissPort;
        agwPort = s.agwPort;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Dire Wolf modem", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Dire Wolf modem running")
                .setContentText("KISS " + kissPort + " / AGW " + agwPort + " on this phone")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    /**
     * The device to use: the saved ID if that device is present, else a present device with
     * the saved name (a re-plugged USB sound card gets a new ID), else 0 (phone's default).
     */
    private int presentDevice(int flags, int savedId, String savedName, String what) {
        if (savedId == 0) return 0;
        AudioManager am = getSystemService(AudioManager.class);
        AudioDeviceInfo[] devices = am.getDevices(flags);
        for (AudioDeviceInfo dev : devices) if (dev.getId() == savedId) return savedId;
        for (AudioDeviceInfo dev : devices)
            if (MainActivity.describe(dev).equals(savedName)) {
                addLogLine("Sound " + what + ": \"" + savedName + "\" is now device " + dev.getId() + ".");
                return dev.getId();
            }
        addLogLine("Sound " + what + ": \"" + savedName + "\" is not connected; using the phone's default.");
        return 0;
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
        ModemSettings settings = ModemSettings.load(this);
        int input = presentDevice(AudioManager.GET_DEVICES_INPUTS, settings.inputId, settings.inputName, "input");
        int output = presentDevice(AudioManager.GET_DEVICES_OUTPUTS, settings.outputId, settings.outputName, "output");
        try {
            try (FileOutputStream out = new FileOutputStream(conf)) {
                out.write(settings.config(input, output).getBytes(StandardCharsets.UTF_8));
            }
            // -t 0: no colour codes in the output.
            addLogLine("Settings: " + settings.speed + " baud, KISS " + settings.kissPort + ", AGW " + settings.agwPort
                    + ", sound in " + (input == 0 ? "default" : input) + ", out " + (output == 0 ? "default" : output));
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
            if (!status.startsWith("Running — program attached")) status = "Running — waiting for a program on port " + kissPort;
        } else if (line.startsWith("Attached to KISS TCP client")) {
            status = "Running — program attached";
        } else if (line.contains("has gone away")) {
            status = "Running — waiting for a program on port " + kissPort;
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
