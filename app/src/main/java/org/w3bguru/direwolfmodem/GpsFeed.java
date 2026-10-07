/*
 * Dire Wolf Modem for Android — runs the Dire Wolf packet modem and TNC.
 * Copyright (C) 2026. GPL-2.0-or-later; see assets/LICENSE.txt.
 */
package org.w3bguru.direwolfmodem;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.Consumer;

/**
 * Feeds the phone's GPS position to Dire Wolf through a named pipe (Dire Wolf's
 * {@code GPSNMEA} setting reads it like a GPS receiver's serial port).
 *
 * Sends the GPS chip's own $GxRMC and $GxGGA lines when the phone gives them; otherwise
 * builds $GPRMC and $GPGGA from Android's GPS location. Dire Wolf keeps the last position
 * it was given with no age limit, so when there is no fresh position this sends $GPGGA
 * with fix quality 0 ("no fix") and Dire Wolf skips its tracker beacons.
 */
final class GpsFeed {
    /** Raw NMEA lines newer than this are being sent, so nothing is built. */
    private static final long NMEA_FRESH_MS = 3000;
    /** A built position older than this counts as lost. */
    private static final long LOCATION_FRESH_MS = 10000;

    private final Context context;
    private final File pipe;
    private final Consumer<String> log;
    private HandlerThread thread;
    private Handler handler;
    private FileDescriptor fd;
    private volatile long lastNmeaAt;
    private volatile Location lastLocation;
    private volatile long lastLocationAt;
    private String shownState = "";

    private final OnNmeaMessageListener nmeaListener = (message, timestamp) -> {
        String line = message.trim();
        if (line.startsWith("$GPRMC") || line.startsWith("$GNRMC")
                || line.startsWith("$GPGGA") || line.startsWith("$GNGGA")) {
            lastNmeaAt = System.currentTimeMillis();
            write(line);
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            lastLocation = location;
            lastLocationAt = System.currentTimeMillis();
        }

        // Needed on Android 9 and older, where these have no default.
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            Location loc = lastLocation;
            if (now - lastNmeaAt < NMEA_FRESH_MS) {
                show("GPS: sending the phone's NMEA lines to Dire Wolf.");
            } else if (loc != null && now - lastLocationAt < LOCATION_FRESH_MS) {
                write(rmc(loc));
                write(gga(loc));
                show("GPS: sending positions built from Android's GPS location to Dire Wolf.");
            } else {
                write(noFix());
                show("GPS: no position yet (no fix); beacons wait for one.");
            }
            handler.postDelayed(this, 1000);
        }
    };

    GpsFeed(Context context, File pipe, Consumer<String> log) {
        this.context = context;
        this.pipe = pipe;
        this.log = log;
    }

    /** Makes a new, empty named pipe. Call before Dire Wolf starts, so it can open it. */
    static void makePipe(File pipe) throws ErrnoException {
        //noinspection ResultOfMethodCallIgnored
        pipe.delete();
        Os.mkfifo(pipe.getAbsolutePath(), 0600);
    }

    /** Starts GPS and the writer. The caller has checked the precise location permission. */
    @SuppressLint("MissingPermission")
    void start() {
        try {
            // Read and write: opening never waits for Dire Wolf, and never fails if it exits.
            // Non-blocking: if Dire Wolf stops reading, lines are dropped instead of waiting.
            fd = Os.open(pipe.getAbsolutePath(), OsConstants.O_RDWR | OsConstants.O_NONBLOCK, 0);
        } catch (ErrnoException e) {
            log.accept("GPS: could not open the pipe for Dire Wolf: " + e.getMessage());
            return;
        }
        thread = new HandlerThread("gps-feed");
        thread.start();
        handler = new Handler(thread.getLooper());
        LocationManager lm = context.getSystemService(LocationManager.class);
        try {
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                log.accept("GPS: the phone's Location setting is off; beacons wait until it is on.");
            }
            lm.addNmeaListener(nmeaListener, handler);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListener, thread.getLooper());
        } catch (SecurityException e) {
            log.accept("GPS: precise location permission is needed for beacons.");
        }
        handler.post(tick);
    }

    void stop() {
        if (handler != null) {
            handler.removeCallbacks(tick);
            LocationManager lm = context.getSystemService(LocationManager.class);
            lm.removeNmeaListener(nmeaListener);
            lm.removeUpdates(locationListener);
            handler.post(() -> {
                write(noFix());
                closePipe();
            });
            thread.quitSafely();
            handler = null;
        } else {
            closePipe();
        }
    }

    private synchronized void closePipe() {
        if (fd == null) return;
        try {
            Os.close(fd);
        } catch (ErrnoException ignored) {
            // Already closed.
        }
        fd = null;
    }

    private void show(String state) {
        if (!state.equals(shownState)) {
            shownState = state;
            log.accept(state);
        }
    }

    private synchronized void write(String sentence) {
        if (fd == null) return;
        byte[] b = (sentence + "\r\n").getBytes(StandardCharsets.US_ASCII);
        try {
            Os.write(fd, b, 0, b.length);
        } catch (Exception ignored) {
            // Pipe full (Dire Wolf not reading): drop this line.
        }
    }

    // ---- NMEA sentences built from an Android Location ----

    static String rmc(Location loc) {
        Calendar t = utc(loc.getTime());
        double knots = loc.hasSpeed() ? loc.getSpeed() * 1.943844 : 0;
        double course = loc.hasBearing() ? loc.getBearing() : 0;
        return withChecksum(String.format(Locale.US, "$GPRMC,%s,A,%s,%.1f,%.1f,%02d%02d%02d,,,A",
                time(t), latLon(loc), knots, course,
                t.get(Calendar.DAY_OF_MONTH), t.get(Calendar.MONTH) + 1, t.get(Calendar.YEAR) % 100));
    }

    static String gga(Location loc) {
        String alt = loc.hasAltitude() ? String.format(Locale.US, "%.1f", loc.getAltitude()) : "";
        return withChecksum(String.format(Locale.US, "$GPGGA,%s,%s,1,08,1.0,%s,M,0.0,M,,",
                time(utc(loc.getTime())), latLon(loc), alt));
    }

    /** $GPGGA with fix quality 0: Dire Wolf reports "Location fix has been lost" and skips tracker beacons. */
    static String noFix() {
        return withChecksum("$GPGGA,,,,,,0,00,,,M,,M,,");
    }

    private static Calendar utc(long millis) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        c.setTimeInMillis(millis);
        return c;
    }

    private static String time(Calendar t) {
        return String.format(Locale.US, "%02d%02d%02d.%02d", t.get(Calendar.HOUR_OF_DAY),
                t.get(Calendar.MINUTE), t.get(Calendar.SECOND), t.get(Calendar.MILLISECOND) / 10);
    }

    /** "ddmm.mmmm,N,dddmm.mmmm,W" */
    static String latLon(Location loc) {
        return degMin(loc.getLatitude(), 2) + "," + (loc.getLatitude() < 0 ? 'S' : 'N') + ","
                + degMin(loc.getLongitude(), 3) + "," + (loc.getLongitude() < 0 ? 'W' : 'E');
    }

    private static String degMin(double value, int degDigits) {
        // Whole ten-thousandths of a minute, so rounding never gives 60.0000 minutes.
        long units = Math.round(Math.abs(value) * 600000);
        long deg = units / 600000, min = units % 600000;
        return String.format(Locale.US, "%0" + degDigits + "d%02d.%04d", deg, min / 10000, min % 10000);
    }

    static String withChecksum(String body) {
        int cs = 0;
        for (int i = 1; i < body.length(); i++) cs ^= body.charAt(i);
        return body + String.format(Locale.US, "*%02X", cs);
    }
}
