/*
 * Dire Wolf Modem for Android — runs the Dire Wolf packet modem and TNC.
 * Copyright (C) 2026. GPL-2.0-or-later; see assets/LICENSE.txt.
 */
package org.w3bguru.direwolfmodem;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 1;
    private static final int LOG_LINES_SHOWN = 20;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private TextView logView;
    private Button startButton;
    private Button stopButton;
    private TextView portsView;
    private TextView settingsNote;
    private EditText kissPort, agwPort, callsign, txDelay, txTail, persist, slotTime, dwait, advanced;
    private EditText beaconMinutes, beaconSymbol, beaconComment;
    private CheckBox beacons;
    private Spinner inputDevice, outputDevice, fec;
    private RadioGroup speed;
    private final List<AudioDeviceInfo> inputs = new ArrayList<>(), outputs = new ArrayList<>();

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            String status = DireWolfService.status();
            statusView.setText("Status: " + status);
            boolean stopped = status.startsWith("Stopped") || status.startsWith("Could not");
            startButton.setEnabled(stopped);
            stopButton.setEnabled(!stopped && !status.startsWith("Stopping"));
            logView.setText(String.join("\n", DireWolfService.lastLogLines(LOG_LINES_SHOWN)));
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        statusView = new TextView(this);
        statusView.setTextSize(16);
        root.addView(statusView);

        portsView = new TextView(this);
        root.addView(portsView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, pad / 2, 0, pad / 2);
        startButton = new Button(this);
        startButton.setText("Start");
        startButton.setOnClickListener(v -> startWithPermissions());
        stopButton = new Button(this);
        stopButton.setText("Stop");
        stopButton.setOnClickListener(v -> startService(
                new Intent(this, DireWolfService.class).setAction(DireWolfService.ACTION_STOP)));
        buttons.addView(startButton);
        buttons.addView(stopButton);
        root.addView(buttons);

        TextView logTitle = new TextView(this);
        logTitle.setText("Dire Wolf log (last " + LOG_LINES_SHOWN + " lines)");
        root.addView(logTitle);

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(10);
        logView.setTextIsSelectable(true);
        root.addView(logView);

        addSettings(root, pad);

        TextView about = new TextView(this);
        about.setPadding(0, pad, 0, 0);
        about.setText("Unofficial Android build of Dire Wolf by John Langner, WB2OSZ, "
                + "version 1.8.1 (git a231971). Free software under the GNU General "
                + "Public License version 2 or later. "
                + "Source: github.com/wb2osz/direwolf plus this app's Android changes.");
        root.addView(about);

        Button licence = new Button(this);
        licence.setText("Show licence");
        licence.setOnClickListener(v -> showLicence());
        root.addView(licence);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    /** The settings form: ports, sound, speed, callsign, timing, error correction, advanced lines. */
    private void addSettings(LinearLayout root, int pad) {
        ModemSettings s = ModemSettings.load(this);
        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextSize(18);
        title.setPadding(0, pad, 0, pad / 4);
        root.addView(title);
        TextView when = new TextView(this);
        when.setText("Changes are used at the next Start. Programs on this phone (FieldMail) must use the same KISS port.");
        root.addView(when);

        kissPort = number(root, "KISS port (1024 to 65535)", s.kissPort);
        agwPort = number(root, "AGW port (1024 to 65535)", s.agwPort);

        AudioManager am = getSystemService(AudioManager.class);
        inputDevice = deviceChoice(root, "Sound input (receive)", am.getDevices(AudioManager.GET_DEVICES_INPUTS), inputs, s.inputId, s.inputName);
        outputDevice = deviceChoice(root, "Sound output (transmit)", am.getDevices(AudioManager.GET_DEVICES_OUTPUTS), outputs, s.outputId, s.outputName);

        label(root, "Speed (baud)");
        speed = new RadioGroup(this);
        speed.setOrientation(RadioGroup.HORIZONTAL);
        for (int baud : ModemSettings.SPEEDS) {
            RadioButton b = new RadioButton(this);
            b.setText(String.valueOf(baud));
            b.setId(baud);
            speed.addView(b);
        }
        speed.check(s.speed);
        root.addView(speed);
        hint(root, "1200: VHF/UHF packet (Winlink gateways). 300: HF packet. 9600: needs the radio's data port.");

        callsign = text(root, "Callsign (MYCALL; empty for NOCALL)", s.callsign, false);
        callsign.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        hint(root, "Used for Dire Wolf's own transmissions such as beacons. FieldMail sends its own callsign.");

        txDelay = number(root, "TX delay, ms (TXDELAY; default 300)", s.txDelayMs);
        txTail = number(root, "TX tail, ms (TXTAIL; default 100)", s.txTailMs);
        persist = number(root, "Persistence, 0 to 255 (PERSIST; default 63)", s.persist);
        slotTime = number(root, "Slot time, ms (SLOTTIME; default 100)", s.slotTimeMs);
        dwait = number(root, "Extra wait before sending, ms (DWAIT; default 0)", s.dwaitMs);
        hint(root, "Dire Wolf counts these times in 10 ms steps.");

        label(root, "Error correction (sending)");
        fec = new Spinner(this);
        fec.setAdapter(listAdapter(java.util.Arrays.asList(ModemSettings.FEC_LABELS)));
        fec.setSelection(s.fec);
        root.addView(fec);
        hint(root, "FX.25 still works with stations that do not have it. IL2P works only with stations that also use IL2P.");

        advanced = text(root, "Advanced: extra Dire Wolf setting lines", s.advanced, true);
        hint(root, "Added at the end of Dire Wolf's settings file, one setting per line. A wrong line can stop Dire Wolf from starting; its log shows why.");

        beacons = new CheckBox(this);
        beacons.setText("Send position beacons (uses GPS)");
        beacons.setChecked(s.beacons);
        beacons.setPadding(0, pad / 2, 0, 0);
        root.addView(beacons);
        hint(root, "Dire Wolf sends this phone's GPS position as an APRS beacon (TBEACON), first 30 s after Start. "
                + "Needs your callsign and precise location. GPS is on only while beacons are on. "
                + "On a radio the beacon is public: anyone can see where the phone is.");
        beaconMinutes = number(root, "Beacon every, minutes (1 to 60)", s.beaconMinutes);
        beaconSymbol = text(root, "APRS symbol (2 characters; /[ person, /> car)", s.beaconSymbol, false);
        beaconComment = text(root, "Beacon comment (optional, up to 40 characters)", s.beaconComment, false);

        Button save = new Button(this);
        save.setText("Save settings");
        save.setOnClickListener(v -> saveSettings());
        root.addView(save);
        settingsNote = new TextView(this);
        root.addView(settingsNote);
    }

    private void saveSettings() {
        ModemSettings s = new ModemSettings();
        try {
            s.kissPort = Integer.parseInt(kissPort.getText().toString().trim());
            s.agwPort = Integer.parseInt(agwPort.getText().toString().trim());
            s.txDelayMs = Integer.parseInt(txDelay.getText().toString().trim());
            s.txTailMs = Integer.parseInt(txTail.getText().toString().trim());
            s.persist = Integer.parseInt(persist.getText().toString().trim());
            s.slotTimeMs = Integer.parseInt(slotTime.getText().toString().trim());
            s.dwaitMs = Integer.parseInt(dwait.getText().toString().trim());
            s.beaconMinutes = Integer.parseInt(beaconMinutes.getText().toString().trim());
        } catch (NumberFormatException e) {
            settingsNote.setText("Not saved: every number field needs a whole number.");
            return;
        }
        int in = inputDevice.getSelectedItemPosition(), out = outputDevice.getSelectedItemPosition();
        s.inputId = in <= 0 ? 0 : inputs.get(in - 1).getId();
        s.inputName = in <= 0 ? "" : describe(inputs.get(in - 1));
        s.outputId = out <= 0 ? 0 : outputs.get(out - 1).getId();
        s.outputName = out <= 0 ? "" : describe(outputs.get(out - 1));
        s.speed = speed.getCheckedRadioButtonId();
        s.callsign = callsign.getText().toString().trim().toUpperCase(java.util.Locale.US);
        s.fec = fec.getSelectedItemPosition();
        s.advanced = advanced.getText().toString();
        s.beacons = beacons.isChecked();
        s.beaconSymbol = beaconSymbol.getText().toString().trim();
        s.beaconComment = beaconComment.getText().toString().trim();
        String problem = s.problem();
        if (problem != null) {
            settingsNote.setText("Not saved: " + problem);
            return;
        }
        s.save(this);
        String state = DireWolfService.status();
        boolean running = !(state.startsWith("Stopped") || state.startsWith("Could not"));
        settingsNote.setText(running ? "Saved. Stop and Start again to use them." : "Saved. They are used at the next Start.");
        showPorts();
    }

    private void showPorts() {
        ModemSettings s = ModemSettings.load(this);
        portsView.setText("KISS port " + s.kissPort + " · AGW port " + s.agwPort + " · this phone only · "
                + s.speed + " baud · sound in: " + (s.inputId == 0 ? "phone's default" : s.inputName)
                + " · sound out: " + (s.outputId == 0 ? "phone's default" : s.outputName));
    }

    /** A sound device as shown in the lists: type, then product name. */
    static String describe(AudioDeviceInfo d) {
        String type;
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: type = "Built-in microphone"; break;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: type = "Built-in speaker"; break;
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: type = "Earpiece"; break;
            case AudioDeviceInfo.TYPE_USB_DEVICE: type = "USB sound card"; break;
            case AudioDeviceInfo.TYPE_USB_HEADSET: type = "USB headset"; break;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: type = "Wired headset"; break;
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: type = "Wired headphones"; break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: type = "Bluetooth (calls)"; break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: type = "Bluetooth (media)"; break;
            default: type = "Type " + d.getType();
        }
        CharSequence name = d.getProductName();
        return type + (name == null || name.length() == 0 ? "" : ": " + name);
    }

    private Spinner deviceChoice(LinearLayout root, String title, AudioDeviceInfo[] devices, List<AudioDeviceInfo> kept, int savedId, String savedName) {
        label(root, title);
        List<String> names = new ArrayList<>();
        names.add("Phone's default");
        int selected = 0;
        for (AudioDeviceInfo dev : devices) {
            int t = dev.getType();
            // Telephony, hearing-aid and similar routes are not usable for a modem.
            if (t == AudioDeviceInfo.TYPE_TELEPHONY || t == AudioDeviceInfo.TYPE_FM_TUNER || t == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) continue;
            kept.add(dev);
            names.add(describe(dev) + " (device " + dev.getId() + ")");
            if (dev.getId() == savedId || (selected == 0 && savedId != 0 && describe(dev).equals(savedName))) selected = kept.size();
        }
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(listAdapter(names));
        spinner.setSelection(selected);
        root.addView(spinner);
        return spinner;
    }

    /** Spinner adapter whose shown item uses the theme's text colour. */
    private ArrayAdapter<String> listAdapter(List<String> items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private EditText number(LinearLayout root, String title, int value) {
        EditText e = text(root, title, String.valueOf(value), false);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private EditText text(LinearLayout root, String title, String value, boolean multiLine) {
        label(root, title);
        EditText e = new EditText(this);
        e.setText(value);
        if (multiLine) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            e.setMinLines(3);
            e.setTypeface(Typeface.MONOSPACE);
        }
        root.addView(e);
        return e;
    }

    private void label(LinearLayout root, String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, 0);
        root.addView(t);
    }

    private void hint(LinearLayout root, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        root.addView(t);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showPorts();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void startWithPermissions() {
        List<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (ModemSettings.load(this).beacons
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Android 12 and newer need both in one request; the person picks Precise.
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (needed.isEmpty()) {
            startForegroundService(new Intent(this, DireWolfService.class));
        } else {
            requestPermissions(needed.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_PERMISSIONS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(new Intent(this, DireWolfService.class));
        } else {
            statusView.setText("Status: Stopped — microphone permission is needed to run the modem");
        }
    }

    private void showLicence() {
        String text;
        try (InputStream in = getAssets().open("LICENSE.txt")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            text = out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            text = "Licence file missing: " + e.getMessage();
        }
        TextView body = new TextView(this);
        body.setText(text);
        body.setTextSize(11);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new AlertDialog.Builder(this)
                .setTitle("GNU General Public License v2")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();
    }
}
