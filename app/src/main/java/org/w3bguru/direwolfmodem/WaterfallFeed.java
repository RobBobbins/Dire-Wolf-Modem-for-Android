/*
 * Dire Wolf Modem for Android — runs the Dire Wolf packet modem and TNC.
 * Copyright (C) 2026. GPL-2.0-or-later; see assets/LICENSE.txt.
 */
package org.w3bguru.direwolfmodem;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Receives the copy of Dire Wolf's received sound (16-bit little-endian samples in UDP datagrams to
 * 127.0.0.1:{@link #PORT}, sent by the native modem when the service sets the environment
 * variable) and hands it to the waterfall. Runs while the screen is visible.
 */
final class WaterfallFeed {
    static final int PORT = 8009;
    static final int RATE = 48000;
    private final WaterfallView view;
    private volatile DatagramSocket socket;
    private volatile Thread thread;

    WaterfallFeed(WaterfallView view) { this.view = view; }

    synchronized void start() {
        if (thread != null) return;
        Thread t = new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket(null)) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));   // the modem sends to IPv4 loopback
                socket = s;
                byte[] b = new byte[4096];
                short[] out = new short[2048];
                DatagramPacket p = new DatagramPacket(b, b.length);
                while (thread == Thread.currentThread()) {
                    p.setLength(b.length);
                    s.receive(p);
                    int n = p.getLength() / 2;
                    for (int i = 0; i < n; i++) out[i] = (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8));
                    view.accept(out, n, RATE);
                }
            } catch (IOException ignored) {
                // closed by stop(), or the port is in use
            }
        }, "waterfall-feed");
        thread = t;
        t.start();
    }

    synchronized void stop() {
        thread = null;
        DatagramSocket s = socket;
        socket = null;
        if (s != null) s.close();
    }
}
