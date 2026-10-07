/*
 * Dire Wolf Modem for Android — runs the Dire Wolf packet modem and TNC.
 * Copyright (C) 2026. GPL-2.0-or-later; see assets/LICENSE.txt.
 */
package org.w3bguru.direwolfmodem;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;
import java.util.function.Consumer;

/**
 * Carries Dire Wolf's PTT changes to the Digirig. Dire Wolf ("PTT ptt.pipe RTS", Android patch
 * in ptt.c) writes one letter per change into this named pipe: R / r = RTS on / off,
 * D / d = DTR on / off. Only RTS keys the Digirig; DTR is ignored. PTT is released when the
 * reader stops.
 */
final class PttPipe {
    /** Written by stop() to wake the reader; Dire Wolf never sends it. */
    private static final byte STOP = 'x';

    private final File pipe;
    private final DigirigPtt ptt;
    private final Consumer<String> log;
    private FileDescriptor fd;
    private Thread reader;

    PttPipe(File pipe, DigirigPtt ptt, Consumer<String> log) {
        this.pipe = pipe;
        this.ptt = ptt;
        this.log = log;
    }

    /** Makes a new, empty named pipe. Call before Dire Wolf starts. */
    static void makePipe(File pipe) throws ErrnoException {
        //noinspection ResultOfMethodCallIgnored
        pipe.delete();
        Os.mkfifo(pipe.getAbsolutePath(), 0600);
    }

    void start() throws ErrnoException {
        // Read and write: opening never waits for Dire Wolf; reads wait for the next letter.
        fd = Os.open(pipe.getAbsolutePath(), OsConstants.O_RDWR, 0);
        reader = new Thread(this::run, "ptt-pipe");
        reader.start();
    }

    private void run() {
        byte[] b = new byte[1];
        try {
            while (true) {
                if (Os.read(fd, b, 0, 1) != 1) break;
                if (b[0] == STOP) break;
                if (b[0] == 'R' || b[0] == 'r') {
                    boolean on = b[0] == 'R';
                    ptt.setPtt(on);
                    log.accept("PTT " + (on ? "on" : "off") + " (Digirig RTS reads " + (ptt.rtsOn() ? "on" : "off") + ")");
                }
            }
        } catch (Exception e) {
            log.accept("PTT stopped working: " + e.getMessage());
        } finally {
            ptt.close();   // releases PTT
        }
    }

    void stop() {
        if (reader == null) {   // start() failed: only the Digirig is open
            ptt.close();
            return;
        }
        try {
            Os.write(fd, new byte[] {STOP}, 0, 1);
            reader.join(2000);
            Os.close(fd);
        } catch (Exception ignored) {
            // Already stopped.
        }
    }
}
