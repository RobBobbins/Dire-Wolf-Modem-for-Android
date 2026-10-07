/*
 * Dire Wolf Modem for Android — runs the Dire Wolf packet modem and TNC.
 * Copyright (C) 2026. GPL-2.0-or-later; see assets/LICENSE.txt.
 */
package org.w3bguru.direwolfmodem;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * The modem's settings, saved on the phone, and the Dire Wolf configuration file made
 * from them. Times are kept in milliseconds; Dire Wolf counts some of them in 10 ms units.
 */
final class ModemSettings {
    static final int[] SPEEDS = {300, 1200, 9600};
    /** Error correction choices: label and the Dire Wolf lines for it. */
    static final String[] FEC_LABELS = {"Off", "FX.25, 16 check bytes", "FX.25, 32 check bytes", "FX.25, 64 check bytes", "IL2P"};
    private static final String[] FEC_LINES = {"", "FX25TX 16\n", "FX25TX 32\n", "FX25TX 64\n", "IL2PTX 1\n"};

    int kissPort = 8101;
    int agwPort = 8100;
    /** Android audio device IDs; 0 means the phone's default. Names are for display and for finding a re-plugged device. */
    int inputId, outputId;
    String inputName = "", outputName = "";
    int speed = 1200;
    String callsign = "";
    int txDelayMs = 300, txTailMs = 100;
    int persist = 63, slotTimeMs = 100, dwaitMs = 0;
    int fec = 0;
    String advanced = "";
    /** Position beacons (Dire Wolf TBEACON) with the phone's GPS position. */
    boolean beacons;
    int beaconMinutes = 10;
    String beaconSymbol = "/[";
    String beaconComment = "";

    static ModemSettings load(Context context) {
        SharedPreferences p = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        ModemSettings s = new ModemSettings();
        s.kissPort = p.getInt("kissPort", s.kissPort);
        s.agwPort = p.getInt("agwPort", s.agwPort);
        s.inputId = p.getInt("inputId", 0);
        s.outputId = p.getInt("outputId", 0);
        s.inputName = p.getString("inputName", "");
        s.outputName = p.getString("outputName", "");
        s.speed = p.getInt("speed", s.speed);
        s.callsign = p.getString("callsign", "");
        s.txDelayMs = p.getInt("txDelayMs", s.txDelayMs);
        s.txTailMs = p.getInt("txTailMs", s.txTailMs);
        s.persist = p.getInt("persist", s.persist);
        s.slotTimeMs = p.getInt("slotTimeMs", s.slotTimeMs);
        s.dwaitMs = p.getInt("dwaitMs", s.dwaitMs);
        s.fec = p.getInt("fec", 0);
        s.advanced = p.getString("advanced", "");
        s.beacons = p.getBoolean("beacons", false);
        s.beaconMinutes = p.getInt("beaconMinutes", s.beaconMinutes);
        s.beaconSymbol = p.getString("beaconSymbol", s.beaconSymbol);
        s.beaconComment = p.getString("beaconComment", "");
        return s;
    }

    void save(Context context) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putInt("kissPort", kissPort).putInt("agwPort", agwPort)
                .putInt("inputId", inputId).putInt("outputId", outputId)
                .putString("inputName", inputName).putString("outputName", outputName)
                .putInt("speed", speed).putString("callsign", callsign)
                .putInt("txDelayMs", txDelayMs).putInt("txTailMs", txTailMs)
                .putInt("persist", persist).putInt("slotTimeMs", slotTimeMs).putInt("dwaitMs", dwaitMs)
                .putInt("fec", fec).putString("advanced", advanced)
                .putBoolean("beacons", beacons).putInt("beaconMinutes", beaconMinutes)
                .putString("beaconSymbol", beaconSymbol).putString("beaconComment", beaconComment)
                .apply();
    }

    /** Returns null when the settings are usable, or what is wrong. */
    String problem() {
        if (kissPort < 1024 || kissPort > 65535) return "KISS port must be 1024 to 65535.";
        if (agwPort < 1024 || agwPort > 65535) return "AGW port must be 1024 to 65535.";
        if (kissPort == agwPort) return "KISS port and AGW port must be different.";
        boolean speedOk = false;
        for (int s : SPEEDS) speedOk |= s == speed;
        if (!speedOk) return "Speed must be 300, 1200 or 9600.";
        if (!callsign.isEmpty() && !callsign.matches("[A-Z0-9]{1,6}(-([0-9]|1[0-5]))?"))
            return "Callsign: up to 6 letters and digits, with an optional -SSID from 0 to 15.";
        if (txDelayMs < 0 || txDelayMs > 2550) return "TX delay must be 0 to 2550 ms.";
        if (txTailMs < 0 || txTailMs > 2550) return "TX tail must be 0 to 2550 ms.";
        if (persist < 0 || persist > 255) return "Persistence must be 0 to 255.";
        if (slotTimeMs < 0 || slotTimeMs > 2550) return "Slot time must be 0 to 2550 ms.";
        if (dwaitMs < 0 || dwaitMs > 2550) return "DWAIT must be 0 to 2550 ms.";
        if (fec < 0 || fec >= FEC_LABELS.length) return "Unknown error correction choice.";
        if (beaconMinutes < 1 || beaconMinutes > 60) return "Beacon every: 1 to 60 minutes.";
        if (!beaconSymbol.matches("[/\\\\A-Z0-9][!-~]") || beaconSymbol.contains("\""))
            return "APRS symbol: 2 characters, a table (/ or \\ or an overlay A-Z, 0-9) then the symbol, for example /[ or />.";
        if (beaconComment.length() > 40 || !beaconComment.matches("[ -~]*") || beaconComment.contains("\""))
            return "Beacon comment: up to 40 plain characters, no \" marks.";
        if (beacons && callsign.isEmpty()) return "Position beacons need your callsign in Callsign (MYCALL).";
        return null;
    }

    /**
     * The Dire Wolf configuration file. inputDevice / outputDevice: the Android device IDs
     * to use (0 = phone's default), already checked against the devices present.
     * gpsPipe: the named pipe GpsFeed writes positions into (used when beacons are on),
     * at most 19 characters, relative to Dire Wolf's working folder.
     */
    String config(int inputDevice, int outputDevice, String gpsPipe) {
        String in = inputDevice == 0 ? "default" : String.valueOf(inputDevice);
        String out = outputDevice == 0 ? "default" : String.valueOf(outputDevice);
        StringBuilder c = new StringBuilder();
        c.append("ADEVICE ").append(in).append(' ').append(out).append('\n');
        c.append("ARATE 48000\n");
        c.append("ACHANNELS 1\n");
        c.append("CHANNEL 0\n");
        c.append("MYCALL ").append(callsign.isEmpty() ? "NOCALL" : callsign).append('\n');
        c.append("MODEM ").append(speed).append('\n');
        c.append(String.format(Locale.US, "TXDELAY %d\nTXTAIL %d\nPERSIST %d\nSLOTTIME %d\nDWAIT %d\n",
                txDelayMs / 10, txTailMs / 10, persist, slotTimeMs / 10, dwaitMs / 10));
        c.append(FEC_LINES[fec]);
        c.append("AGWPORT ").append(agwPort).append('\n');
        // KISSPORT 0 removes Dire Wolf's default KISS port 8001, so only kissPort is open.
        c.append("KISSPORT 0\n");
        c.append("KISSPORT ").append(kissPort).append('\n');
        if (beacons) {
            // The app writes the phone's GPS position into this named pipe (GpsFeed).
            // Speed 0: leave the "port" settings alone (it is a pipe, not a serial port).
            c.append("GPSNMEA ").append(gpsPipe).append(" 0\n");
            c.append(String.format(Locale.US, "TBEACON delay=0:30 every=%d:00 symbol=\"%s\"", beaconMinutes, beaconSymbol));
            if (!beaconComment.isEmpty()) c.append(" comment=\"").append(beaconComment).append('"');
            c.append('\n');
        }
        if (!advanced.trim().isEmpty()) {
            c.append("# Advanced settings from the app\n").append(advanced.trim()).append('\n');
        }
        return c.toString();
    }
}
