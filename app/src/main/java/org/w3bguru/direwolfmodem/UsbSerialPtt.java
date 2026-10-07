/*
 * Dire Wolf Modem for Android — runs the Dire Wolf packet modem and TNC.
 * Copyright (C) 2026. GPL-2.0-or-later; see assets/LICENSE.txt.
 */
package org.w3bguru.direwolfmodem;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.util.List;
import java.util.Locale;

/**
 * PTT on the RTS or DTR line of a USB serial port (any chip usb-serial-for-android drives:
 * CP210x, FTDI, CH34x, Prolific, CDC). Closing releases PTT.
 */
final class UsbSerialPtt implements AutoCloseable {
    static final String ACTION_USB_PERMISSION = "org.w3bguru.direwolfmodem.USB_PERMISSION";

    private final UsbSerialPort port;
    private final boolean dtr;

    private UsbSerialPtt(UsbSerialPort port, boolean dtr) {
        this.port = port;
        this.dtr = dtr;
    }

    /** USB serial ports plugged in. */
    static List<UsbSerialDriver> ports(Context context) {
        return UsbSerialProber.getDefaultProber().findAllDrivers(context.getSystemService(UsbManager.class));
    }

    /** Key a port is saved under: vendor and product IDs and the product name (no permission needed). */
    static String key(UsbDevice d) {
        String name = d.getProductName() == null ? "" : d.getProductName().replaceAll("\\p{Cntrl}", "").trim();
        return String.format(Locale.US, "%04X:%04X %s", d.getVendorId(), d.getProductId(), name);
    }

    /** A saved key as shown on screen: the product name, or the IDs. */
    static String label(String key) {
        int space = key.indexOf(' ');
        String name = space < 0 ? "" : key.substring(space + 1);
        return name.isEmpty() ? "USB serial " + key.trim() : name;
    }

    /** The port saved under key (the first port found when key is empty), or null. */
    static UsbDevice find(Context context, String key) {
        UsbDevice first = null;
        for (UsbSerialDriver d : ports(context)) {
            if (first == null) first = d.getDevice();
            if (key(d.getDevice()).equals(key)) return d.getDevice();
        }
        return key.isEmpty() ? first : null;
    }

    /** Asks Android for permission to use the device; the person answers in a system box. */
    static void requestPermission(Context context, UsbDevice device) {
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE);
        context.getSystemService(UsbManager.class).requestPermission(device, pi);
    }

    /** Opens the port with PTT off (RTS and DTR low). The caller has permission. */
    static UsbSerialPtt open(Context context, UsbDevice device, boolean dtr) {
        UsbManager usb = context.getSystemService(UsbManager.class);
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null || driver.getPorts().isEmpty()) throw new IllegalStateException("this USB serial chip is not supported");
        UsbDeviceConnection c = usb.openDevice(device);
        if (c == null) throw new IllegalStateException("Android could not open the USB serial port");
        UsbSerialPort port = driver.getPorts().get(0);
        try {
            port.open(c);
            port.setDTR(false);
            port.setRTS(false);
        } catch (Exception e) {
            try { port.close(); } catch (Exception ignored) { }
            throw new IllegalStateException("could not open the USB serial port: " + e.getMessage());
        }
        return new UsbSerialPtt(port, dtr);
    }

    /** "RTS" or "DTR", for logs. */
    String line() {
        return dtr ? "DTR" : "RTS";
    }

    void setPtt(boolean on) {
        try {
            if (dtr) port.setDTR(on); else port.setRTS(on);
        } catch (Exception e) {
            throw new IllegalStateException(line() + " could not be set: " + e.getMessage());
        }
    }

    /** The PTT line as the chip reports it. */
    boolean pttOn() {
        try {
            return port.getControlLines().contains(dtr ? UsbSerialPort.ControlLine.DTR : UsbSerialPort.ControlLine.RTS);
        } catch (Exception e) {
            throw new IllegalStateException("could not read the " + line() + " line: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        try {
            setPtt(false);
        } catch (RuntimeException ignored) {
            // Device may already be gone.
        }
        try { port.close(); } catch (Exception ignored) { }
    }
}
