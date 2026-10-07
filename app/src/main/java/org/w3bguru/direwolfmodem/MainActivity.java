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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

        TextView ports = new TextView(this);
        ports.setText("KISS port " + DireWolfService.KISS_PORT
                + " · AGW port " + DireWolfService.AGW_PORT + " · this phone only · 1200 baud · "
                + "phone's own microphone and speaker");
        root.addView(ports);

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

    @Override
    protected void onResume() {
        super.onResume();
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
