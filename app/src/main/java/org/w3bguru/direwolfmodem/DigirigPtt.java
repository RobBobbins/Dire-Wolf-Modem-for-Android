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
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

/**
 * PTT on the Digirig: its CP2102N serial chip's RTS line keys the radio.
 * Uses the CP210x vendor requests directly (no USB serial library):
 * IFC_ENABLE (0x00), SET_MHS (0x07, set modem handshake lines), GET_MDMSTS (0x08, read them).
 */
final class DigirigPtt implements AutoCloseable {
    static final String ACTION_USB_PERMISSION = "org.w3bguru.direwolfmodem.USB_PERMISSION";
    private static final int SILABS_VENDOR_ID = 0x10C4;
    private static final int REQTYPE_HOST_TO_DEVICE = 0x41, REQTYPE_DEVICE_TO_HOST = 0xC1;
    private static final int IFC_ENABLE = 0x00, SET_MHS = 0x07, GET_MDMSTS = 0x08;
    // SET_MHS value: bit 0 DTR, bit 1 RTS; bits 8 and 9 say which of them to change.
    private static final int MHS_DTR_MASK = 0x0100, MHS_RTS_MASK = 0x0200, MHS_RTS = 0x0002;

    private final UsbDeviceConnection connection;
    private final UsbInterface intf;

    private DigirigPtt(UsbDeviceConnection connection, UsbInterface intf) {
        this.connection = connection;
        this.intf = intf;
    }

    /** The first Silicon Labs CP210x serial chip plugged in, or null. */
    static UsbDevice findDevice(Context context) {
        UsbManager usb = context.getSystemService(UsbManager.class);
        for (UsbDevice d : usb.getDeviceList().values()) if (d.getVendorId() == SILABS_VENDOR_ID) return d;
        return null;
    }

    /** Asks Android for permission to use the device; the person answers in a system box. */
    static void requestPermission(Context context, UsbDevice device) {
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE);
        context.getSystemService(UsbManager.class).requestPermission(device, pi);
    }

    /** Opens the chip with PTT off (RTS and DTR low). The caller has permission. */
    static DigirigPtt open(Context context, UsbDevice device) {
        UsbDeviceConnection c = context.getSystemService(UsbManager.class).openDevice(device);
        if (c == null) throw new IllegalStateException("Android could not open the Digirig serial port");
        UsbInterface intf = device.getInterface(0);
        if (!c.claimInterface(intf, true)) {
            c.close();
            throw new IllegalStateException("Could not claim the Digirig serial port");
        }
        DigirigPtt p = new DigirigPtt(c, intf);
        p.control(IFC_ENABLE, 1);
        p.control(SET_MHS, MHS_DTR_MASK | MHS_RTS_MASK);   // DTR and RTS off
        return p;
    }

    void setPtt(boolean on) {
        control(SET_MHS, MHS_RTS_MASK | (on ? MHS_RTS : 0));
    }

    /** RTS as the chip reports it (GET_MDMSTS bit 1). */
    boolean rtsOn() {
        byte[] b = new byte[1];
        int n = connection.controlTransfer(REQTYPE_DEVICE_TO_HOST, GET_MDMSTS, 0, intf.getId(), b, 1, 1000);
        if (n != 1) throw new IllegalStateException("Could not read the Digirig's line state (" + n + ")");
        return (b[0] & 0x02) != 0;
    }

    private void control(int request, int value) {
        int n = connection.controlTransfer(REQTYPE_HOST_TO_DEVICE, request, value, intf.getId(), null, 0, 1000);
        if (n < 0) throw new IllegalStateException("Digirig serial request 0x" + Integer.toHexString(request) + " failed");
    }

    @Override
    public void close() {
        try {
            setPtt(false);
        } catch (RuntimeException ignored) {
            // Device may already be gone.
        }
        connection.releaseInterface(intf);
        connection.close();
    }
}
