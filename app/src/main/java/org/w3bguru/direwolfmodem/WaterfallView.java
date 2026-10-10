/*
 * Dire Wolf Modem for Android — runs the Dire Wolf packet modem and TNC.
 * Copyright (C) 2026. GPL-2.0-or-later; see assets/LICENSE.txt.
 */
package org.w3bguru.direwolfmodem;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.View;

/**
 * Scrolling waterfall of the received sound, 0 to 3,000 Hz, newest line at the top. Fed with
 * {@link #accept} from any thread (Dire Wolf's sound, through WaterfallFeed); the spectrum is
 * worked out on its own thread so the modem is never held up. Marks the modem's band.
 */
public final class WaterfallView extends View {
    private static final float RANGE_DB = 45f;
    private static final long IDLE_MS = 2000;

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint();
    private final Paint centre = new Paint();
    private final Paint scaleBack = new Paint();
    private final int scaleHeight;
    private final int[] palette = new int[256];

    private final Object lock = new Object();
    private int[] pixels;           // width x rows, guarded by lock
    private int width, rows;
    private Bitmap bitmap;          // UI thread only

    private HandlerThread worker;
    private Handler handler;
    private float[] block;          // filled by accept(), guarded by itself
    private int blockFill, blockRate;
    private float floorDb = Float.NaN;
    private volatile long lastLineAt;
    private volatile int maxHz = 3000;
    private volatile float markLow = 1250, markHigh = 1750;
    private String idleText = "Waterfall: shows the sound while Dire Wolf runs.";

    public WaterfallView(Context context) {
        super(context);
        float sp = context.getResources().getDisplayMetrics().scaledDensity;
        float dp = context.getResources().getDisplayMetrics().density;
        scaleHeight = Math.round(26 * dp);
        text.setColor(Color.WHITE); text.setTextSize(15 * sp);
        edge.setColor(Color.rgb(255, 80, 80)); edge.setStrokeWidth(Math.max(2f, 2 * dp));
        centre.setColor(Color.rgb(255, 80, 80)); centre.setStrokeWidth(Math.max(1f, dp));
        centre.setPathEffect(new DashPathEffect(new float[] {6 * dp, 6 * dp}, 0));
        scaleBack.setColor(Color.rgb(25, 25, 25));
        setBackgroundColor(Color.rgb(0, 0, 40));
        // dark blue -> blue -> cyan -> yellow -> red
        int[][] stops = {{0, 0, 40}, {0, 40, 200}, {0, 220, 230}, {250, 240, 0}, {255, 40, 0}};
        for (int i = 0; i < 256; i++) {
            float t = i / 255f * (stops.length - 1);
            int k = Math.min(stops.length - 2, (int) t);
            float f = t - k;
            palette[i] = Color.rgb(Math.round(stops[k][0] + f * (stops[k + 1][0] - stops[k][0])),
                    Math.round(stops[k][1] + f * (stops[k + 1][1] - stops[k][1])),
                    Math.round(stops[k][2] + f * (stops[k + 1][2] - stops[k][2])));
        }
        setContentDescription("Waterfall of the received sound");
    }

    /** The modem's band, drawn as two lines with a dashed centre (none when highHz is not above lowHz), and the top of the frequency scale in Hz. */
    public void setBand(float lowHz, float highHz, int topHz) {
        markLow = lowHz; markHigh = highHz;
        if (topHz != maxHz) {
            maxHz = topHz;
            synchronized (lock) { if (pixels != null) java.util.Arrays.fill(pixels, palette[0]); }
        }
        postInvalidate();
    }

    public void setIdleText(String value) { idleText = value; postInvalidate(); }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        worker = new HandlerThread("waterfall");
        worker.start();
        handler = new Handler(worker.getLooper());
    }

    @Override protected void onDetachedFromWindow() {
        if (worker != null) worker.quitSafely();
        worker = null; handler = null;
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        synchronized (lock) {
            width = Math.max(1, w); rows = Math.max(1, h - scaleHeight);
            pixels = new int[width * rows];
            java.util.Arrays.fill(pixels, palette[0]);
        }
        bitmap = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h - scaleHeight), Bitmap.Config.ARGB_8888);
    }

    /** One block of received 16-bit samples. Any thread; returns at once. */
    public void accept(short[] samples, int count, int sampleRate) {
        Handler h = handler;
        if (h == null || count <= 0 || sampleRate <= 0) return;
        float[] full = null;
        int rate;
        synchronized (this) {
            if (block == null || blockRate != sampleRate) {
                int n = 1024;
                while (n * 48000L < 4096L * sampleRate) n <<= 1;   // about 11.7 Hz per bin at any rate
                block = new float[n]; blockFill = 0; blockRate = sampleRate;
            }
            rate = blockRate;
            for (int i = 0; i < count; i++) {
                block[blockFill++] = samples[i];
                if (blockFill == block.length) {
                    full = block.clone();
                    blockFill = 0;
                }
            }
        }
        if (full != null) {
            final float[] data = full; final int r = rate;
            h.post(() -> addLine(data, r));
        }
    }

    private void addLine(float[] x, int rate) {
        int n = x.length;
        float[] re = new float[n], im = new float[n];
        for (int i = 0; i < n; i++) re[i] = x[i] * (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)));
        fft(re, im);
        int bins = Math.min(n / 2, maxHz * n / rate);
        float[] db = new float[bins];
        for (int k = 0; k < bins; k++) db[k] = (float) (10 * Math.log10(re[k] * re[k] + im[k] * im[k] + 1e-3));
        float[] sorted = db.clone();
        java.util.Arrays.sort(sorted);
        float p20 = sorted[bins / 5];
        floorDb = Float.isNaN(floorDb) ? p20 : floorDb * 0.9f + p20 * 0.1f;
        synchronized (lock) {
            if (pixels == null) return;
            System.arraycopy(pixels, 0, pixels, width, width * (rows - 1));
            for (int col = 0; col < width; col++) {
                int a = col * bins / width, b = Math.max(a + 1, (col + 1) * bins / width);
                float best = db[a];
                for (int k = a + 1; k < b && k < bins; k++) best = Math.max(best, db[k]);
                int level = Math.round((best - floorDb) / RANGE_DB * 255);
                pixels[col] = palette[Math.max(0, Math.min(255, level))];
            }
        }
        lastLineAt = SystemClock.elapsedRealtime();
        postInvalidate();
    }

    /** In-place radix-2 FFT; n is a power of two. */
    static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { float t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int p = i + k, q = p + len / 2;
                    float xr = re[q] * cr - im[q] * ci, xi = re[q] * ci + im[q] * cr;
                    re[q] = re[p] - xr; im[q] = im[p] - xi;
                    re[p] += xr; im[p] += xi;
                    float t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                }
            }
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight(), top = h - scaleHeight;
        if (bitmap != null) {
            synchronized (lock) {
                if (pixels != null && bitmap.getWidth() == width && bitmap.getHeight() == rows)
                    bitmap.setPixels(pixels, 0, width, 0, 0, width, rows);
            }
            canvas.drawBitmap(bitmap, 0, 0, null);
        }
        // band of the modem
        int topHz = maxHz;
        float lo = markLow / topHz * w, hi = markHigh / topHz * w, mid = (markLow + markHigh) / 2 / topHz * w;
        if (markHigh > markLow) {   // no lines when the band is not known
            canvas.drawLine(Math.max(1, lo), 0, Math.max(1, lo), top, edge);
            canvas.drawLine(Math.min(w - 1, hi), 0, Math.min(w - 1, hi), top, edge);
            canvas.drawLine(mid, 0, mid, top, centre);
        }
        // frequency scale
        canvas.drawRect(0, top, w, h, scaleBack);
        float base = top + (scaleHeight + text.getTextSize()) / 2 - 3;
        for (int hz = 500; hz <= topHz - 400; hz += 500) {
            String label = String.valueOf(hz);
            float x = (float) hz / topHz * w - text.measureText(label) / 2;
            canvas.drawText(label, x, base, text);
        }
        canvas.drawText("Hz", w - text.measureText("Hz") - 4, base, text);
        if (SystemClock.elapsedRealtime() - lastLineAt > IDLE_MS) {
            float tw = text.measureText(idleText);
            if (tw > w - 16) {
                // two lines when it does not fit
                int cut = idleText.lastIndexOf(' ', idleText.length() / 2 + 8);
                if (cut < 0) cut = idleText.length() / 2;
                String a = idleText.substring(0, cut), b = idleText.substring(cut + 1);
                canvas.drawText(a, (w - text.measureText(a)) / 2, top / 2f - 4, text);
                canvas.drawText(b, (w - text.measureText(b)) / 2, top / 2f + text.getTextSize(), text);
            } else canvas.drawText(idleText, (w - tw) / 2, top / 2f + text.getTextSize() / 2, text);
        } else postInvalidateDelayed(IDLE_MS + 100);   // shows the idle text once the sound stops
    }
}
