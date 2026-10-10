# Dire Wolf Modem for Android

Android build of the Dire Wolf packet modem and TNC, version 1.8.1 (git a231971, November 2025). Separate from the FieldMail app: FieldMail talks to this modem only over Dire Wolf's standard network ports on the phone, so FieldMail contains no Dire Wolf code.

Status 2026-10-10: steps B1 (built and checked), B2 (packets both ways through the air), B3 (the app), B4 (FieldMail sends Winlink messages through it), B5 (PTT on a USB serial port, checked without a radio) and B6 (GPS position beacons) done. Since then: input level, a partial wake lock so the modem keeps working with the screen off, and a waterfall on the main screen. The plan (steps B1 to B6) is in FieldMail's `PACKET-STATUS.md`.

## Source

`upstream/` is a plain copy of https://github.com/wb2osz/direwolf at tag `1.8.1`, commit `a231971a652bfb574a4bae9a5d875fbce53d2267`, without its git history. The only changes to upstream files are in `patches/0001-android-loopback.patch` and `patches/0002-android-ptt-pipe.patch` (see below). The Android build replaces one file and fills header gaps from `port/`.

To move to a newer Dire Wolf release: replace `upstream/` with a copy of the new release, apply `patches/0001-android-loopback.patch` and `patches/0002-android-ptt-pipe.patch`, check `CMakeLists.txt` against the new `upstream/src/CMakeLists.txt` source lists, rebuild, and repeat the checks below.

## Licence

Dire Wolf is GPL-2.0-or-later (`upstream/LICENSE`, copied to `LICENSE`). Everything in this folder is GPL-2.0-or-later. Anyone given a copy of this modem must also be offered its full source, including the files in `port/`.

## What is here

| Path | What it is |
|------|------------|
| `upstream/` | Copy of Dire Wolf 1.8.1 (see Source above) with the patch below applied |
| `patches/0001-android-loopback.patch` | Change to Dire Wolf's own files: on Android, the KISS and AGW ports listen on 127.0.0.1 only (`upstream/src/kissnet.c`, `upstream/src/server.c`, inside `#if __ANDROID__`), so other devices on the phone's network cannot connect and make it transmit |
| `patches/0002-android-ptt-pipe.patch` | Change to Dire Wolf's own files: on Android, serial PTT (`PTT <device> RTS`) writes one letter per change (R/r RTS on/off, D/d DTR on/off) into a named pipe made by the app, which keys the Digirig over USB (`upstream/src/ptt.c`, inside `#elif __ANDROID__` and `#if __ANDROID__`) |
| `app/` | The Android app "Dire Wolf Modem" (`org.w3bguru.direwolfmodem`): screen with **Start** / **Stop**, live log, licence; a foreground service runs the program as `libdirewolf.so` with the settings below |
| `port/audio_aaudio.c` | Android sound: the six `audio_*` functions of `upstream/src/audio.c` on AAudio (blocking reads and writes, 16-bit PCM). `ADEVICE default` uses Android's default input and output; a number selects that Android audio device ID. `stdin` and `udp:port` input work as in `audio.c` (UDP bound to 127.0.0.1 only) |
| `port/android_config.h` | Name and version strings that upstream normally gets from its CMake file, and `HAVE_STRLCPY` / `HAVE_STRLCAT` (Android's C library has both) |
| `port/include/sys/soundcard.h` | Empty stand-in: `direwolf.c` includes it but uses nothing from it |
| `port/include/sys/termios.h` | Stand-in that includes `<termios.h>` (`ptt.c` includes the `sys/` name, which Android does not have) |
| `CMakeLists.txt` | NDK build of `direwolf` and `atest` for arm64 (the app build runs it too) |

Left out of the build: gpsd, hamlib, libgpiod, udev/CM108 and DNS-SD (all optional in upstream), and upstream's `external/misc` (not needed on Android).

## Build (PC, Git Bash, folder `E:\new-w3bguru-websites\direwolf-android`)

```bash
CM=/c/Users/Robert/AppData/Local/Android/Sdk/cmake/3.22.1/bin
NDK=/c/Users/Robert/AppData/Local/Android/Sdk/ndk/28.2.13676358
"$CM/cmake.exe" -S . -B build/arm64 -G Ninja -DCMAKE_MAKE_PROGRAM="$CM/ninja.exe" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release
"$CM/cmake.exe" --build build/arm64
"$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" -o build/arm64/direwolf-stripped build/arm64/direwolf
```

## Results, 2026-10-07 (phone SM-G981W, wireless ADB, programs in `/data/local/tmp/dw`)

- Size: `direwolf` 530,824 bytes stripped (247,531 bytes gzip -9); `atest` 108,448 bytes stripped. Needs only Android system libraries (libaaudio, liblog, libm, libc).
- `direwolf -h` prints "Dire Wolf Release 1.8.1, November 2025".
- `atest` on Dire Wolf's 100-packet noise test (`gen_packets -r 48000 -n 100`, made on the PC): **70 decoded**, the same as Dire Wolf 1.8.1 on Windows.
- `atest` on FieldMail's own 1200 baud test file: 3 of 3 decoded.
- Sound: `direwolf` with `ADEVICE default` and `ARATE 48000` opened AAudio input device 19 (built-in microphone) and output device 3 (built-in speaker), 48,000 samples per second, mono, 16 bit; listened 8 seconds, nothing transmitted.

- Through the air (step B2), phone built-in speaker and microphone beside the PC (Realtek speakers, USB sound card microphone), PC running Dire Wolf 1.8.1 for Windows:
  - Phone to PC: `CBEACON delay=0:04 every=0:08 dest=VA3OSO info="DW-ANDROID-TX"` from VA3OZO; the PC decoded 4 of 4 (audio level 41 to 42).
  - PC to phone: 3 UI frames "DIREWOLF-PC-TX 1..3" sent through `kissutil`; the phone decoded 3 of 3 (audio level 45 to 46).
  - Run on the phone as `timeout -s INT <seconds> ./direwolf -t 0 -c <file>`: SIGINT lets Dire Wolf write out its screen log before it stops.

## The app (step B3)

Build: `gradlew.bat assembleDebug assembleRelease` in this folder (it builds and strips the program first). Release APK 282,584 bytes unsigned (with the settings form).

### Settings (on the app screen, under **Settings**, saved with **Save settings**, used at the next **Start**)

| Field | Dire Wolf setting | Default |
|---|---|---|
| **KISS port (1024 to 65535)** | `KISSPORT` (after `KISSPORT 0`, which removes Dire Wolf's default 8001) | 8101 |
| **AGW port (1024 to 65535)** | `AGWPORT` | 8100 |
| **Sound input (receive)** / **Sound output (transmit)** | `ADEVICE` (Android audio device ID, or `default`) | Phone's default |
| **Speed (baud)**: 300, 1200, 9600 | `MODEM` | 1200 |
| **Callsign (MYCALL; empty for NOCALL)** | `MYCALL` | NOCALL |
| **TX delay, ms** / **TX tail, ms** | `TXDELAY` / `TXTAIL` (10 ms units) | 300 / 100 |
| **Persistence, 0 to 255** / **Slot time, ms** / **Extra wait before sending, ms** | `PERSIST` / `SLOTTIME` / `DWAIT` | 63 / 100 / 0 |
| **Error correction (sending)**: Off, FX.25 with 16, 32 or 64 check bytes, IL2P | `FX25TX 16/32/64`, `IL2PTX 1` | Off |
| **Advanced: extra Dire Wolf setting lines** | added at the end of the file | empty |
| **Send position beacons (uses GPS)** | `GPSNMEA gps.pipe 0` and `TBEACON delay=0:30 every=<minutes>:00 symbol="<symbol>"` (see GPS below) | off |
| **Beacon every, minutes (1 to 60)** | `TBEACON every=` | 10 |
| **APRS symbol (2 characters; /[ person, /> car)** | `TBEACON symbol=` (table and symbol characters; names such as "car" need a file the app does not have) | `/[` |
| **Beacon comment (optional, up to 40 characters)** | `TBEACON comment=` | empty |

Always written: `ARATE 48000`, `ACHANNELS 1`, `CHANNEL 0`. No PTT yet.

Checks on save: ports 1024 to 65535 and different from each other, callsign up to 6 letters and digits with an optional -SSID 0 to 15, times 0 to 2550 ms, persistence 0 to 255, beacon interval 1 to 60 minutes, symbol of 2 characters (table `/`, `\` or an overlay A-Z, 0-9, then the symbol), comment up to 40 plain characters without `"`, and a callsign when beacons are on. A sound device is saved by ID and name; at Start, a device that is no longer present is looked up by name (a re-plugged USB sound card gets a new ID), else the phone's default is used and the log says so.

Checked on the phone 2026-10-07: **Start** asks for the microphone (and notification) permission, then shows "Running — waiting for a program on port 8101" and the "Dire Wolf modem running" notification; `/proc/net/tcp` shows only 127.0.0.1:8100 and 127.0.0.1:8101 listening; a connection from the phone itself is "Attached to KISS TCP client"; a connection from the PC over Wi-Fi to the phone's address, port 8101, is refused; **Stop** ends the program, closes both ports, removes the notification and shows "Stopped". Settings: KISS port 8111 saved and used (Dire Wolf "Ready to accept KISS TCP client application 0 on port 8111", 127.0.0.1:8111 listening, settings file matches the screen); KISS port 8100 refused with "Not saved: KISS port and AGW port must be different."; back to 8101; the **Sound input** list offers "Phone's default" and the two built-in microphones (devices 19 and 21).

## FieldMail through this app (step B4)

FieldMail's **Packet Winlink Session** has **Modem** "Dire Wolf Modem app" and a **Dire Wolf KISS port** field (8101 by default; it must match this app's KISS port). FieldMail connects to 127.0.0.1 on that port and runs its own AX.25 connection and Winlink session; this app does the tones.

Checked 2026-10-07 (FieldMail run `b4-01`): this app running with the phone's speaker and microphone, FieldMail called VA3OSO, through the air to Dire Wolf on the PC and Winlink Express in Packet P2P: one Winlink message each way, "Sent 1, received 1", 24 seconds, no resends.

## GPS and position beacons (step B6)

When **Send position beacons (uses GPS)** is on, the service makes a named pipe `gps.pipe` in the app's files folder before Dire Wolf starts, and Dire Wolf reads it as its GPS receiver (`GPSNMEA gps.pipe 0`). No change to Dire Wolf's files was needed: on Android it opens the "serial port" as a plain file and only warns when the port settings cannot be set (the log shows `tcgetattr: Permission denied` and `tcsetattr: Permission denied`; harmless).

- The name is given relative to Dire Wolf's working folder (the files folder) because Dire Wolf keeps only 19 characters of the GPS device name (`upstream/src/config.h`, `gpsnmea_port[20]`); the full path was cut off on the first try.
- `GpsFeed.java` writes the phone GPS chip's own `$GPRMC`/`$GNRMC` and `$GPGGA`/`$GNGGA` lines into the pipe. If the phone gives no NMEA lines, it builds `$GPRMC` and `$GPGGA` from Android's GPS location once a second.
- Dire Wolf keeps the last position it was given with no age limit, and takes the fix from `$GPGGA`. So with no fresh position (no NMEA for 3 s and no location for 10 s), and at **Stop**, the app sends `$GPGGA` with fix quality 0; Dire Wolf prints "Location fix has been lost" and skips tracker beacons until a fix returns.
- Permissions: precise location "While using the app" (asked at **Start** only when beacons are on), `FOREGROUND_SERVICE_LOCATION`; the service type is microphone plus location only while beacons are on. GPS is used only while beacons are on. Without the permission the run starts with beacons off and the log says so.
- Privacy: a position beacon carries the phone's real position. On a radio it is public.

Checked 2026-10-07 (phone SM-G981W, indoors): Dire Wolf on the phone printed "Location fix is now 3D" (the phone's own NMEA lines are used) and sent `VA3OZO>APDW18:!<position>[`. Acoustic run `b6-02`: Dire Wolf 1.8.1 on the PC decoded 2 beacons from VA3OZO, 61 s apart, the first 26 s after **Start**, audio level 39, symbol `/[`, the phone's latitude and longitude. Release APK 286,964 bytes unsigned.

Dire Wolf's screen output is fully buffered when it goes to a file (`textcolor.c` writes with `fputs` and no flush), so lines can arrive late in the app's log, and can be lost when a run is killed. For PC test evidence use Dire Wolf's `-L <file>` packet log, which is flushed after every packet.

## Digirig on the phone (start of step B5), 2026-10-07

No radio connected. The phone sees the Digirig as two USB devices: a C-Media sound card ("USB PnP Sound Device", 0x0D8C:0x013C; Android type USB headset, output 2860 and input 2864 on this phone) and a Silicon Labs CP2102N serial chip (0x10C4:0xEA60).

- Sound: with **Sound input (receive)** and **Sound output (transmit)** set to the USB headset, the app opened both at 48,000 samples per second and sent 3 test packets (`CBEACON` in the advanced lines); the settings were then put back to **Phone's default**. A separate 14 s run of the command-line program with `-a 3` showed 48.0 k samples per second and 0 errors on the input; the input level rose (15, 8) only while the phone was sending: a small leak of the transmit sound into the input.
- With the Digirig plugged in, Android sends media and notification sounds to it, and ringtones and alarms to it and the speaker.
- PTT: **PTT (Digirig)** section, **Test PTT (2 seconds)** (`DigirigPtt.java`): CP210x vendor requests through Android's USB host calls (no library): IFC_ENABLE, SET_MHS for RTS, GET_MDMSTS to read it back. Asks for USB permission on first use. Result on the phone: "RTS (PTT) read back from the Digirig: before off, during on, after off. PTT works." Dire Wolf itself does not key PTT yet. Release APK 288,856 bytes unsigned.

## PTT from Dire Wolf (step B5), 2026-10-07

Setting **PTT (keying the radio)**: "None (no radio, or the radio's VOX)" (default) or "Digirig (RTS on its USB serial port)". With Digirig, **Start** makes the named pipe `ptt.pipe`, opens the Digirig's CP2102N (USB permission must already be allowed: tap **Test PTT** once), and writes `PTT ptt.pipe RTS`. Dire Wolf (patch 0002) writes R or r into the pipe on each PTT change; `PttPipe.java` sets RTS and logs "PTT on (Digirig RTS reads on)" / "PTT off (Digirig RTS reads off)". **Stop**, or Dire Wolf exiting, releases RTS. **Test PTT** refuses while the modem holds the Digirig. If the Digirig is missing or not allowed, the run starts with PTT off and the log says why.

Checked 2026-10-07 (run `ptt-01`, Digirig on the phone, no radio): sound in and out on the Digirig, a test packet every 10 s: the log showed "PTT on (Digirig RTS reads on)", the packet, "PTT off (Digirig RTS reads off)" for each of 3 packets; **Stop** during a 4th packet, after which **Test PTT** read RTS "before off" (released). Not measured: the time between RTS and the start of the audio (TXDELAY 300 ms covers it; to check with a radio). Release APK 289,864 bytes unsigned.

## PTT on any USB serial port (2026-10-07)

PTT no longer depends on the Digirig. Setting **PTT (keying the radio)**: "None (no radio, or the radio's VOX)", "USB serial port, RTS line" or "USB serial port, DTR line"; **PTT serial port**: "First USB serial port found" or a plugged-in port by its product name and USB IDs (saved by IDs and name). The section **PTT test** with **Test PTT (2 seconds)** uses the saved line and port. `UsbSerialPtt.java` (replaces `DigirigPtt.java`) uses the library usb-serial-for-android 3.11.0 (MIT licence, from jitpack.io, the same as FieldMail; it ships its own keep rule for the release build), so CP210x, FTDI, CH34x, Prolific and CDC chips work. Dire Wolf is still configured `PTT ptt.pipe RTS`; the app applies the chosen line. Release APK 316,693 bytes unsigned (+26,829 for the library).

Checked on the phone (Digirig, no radio): port list "CP2102N USB to UART Bridge Controller (USB 10C4:EA60)"; DTR: "DTR (PTT) read back from CP2102N USB to UART Bridge Controller: before off, during on, after off. PTT works."; RTS with Dire Wolf running and a test packet every 10 s: "PTT on (RTS reads on)", packet, "PTT off (RTS reads off)" for each of 3 packets. Not tested: other chip types (none on hand), keying a real radio. Settings put back to PTT None and the first port.

## Packets list on the screen (2026-10-07)

The screen shows **Packets (newest first)** in large text: one entry per packet heard or sent, with the time and Dire Wolf's audio level ("level 200 (too loud)"; good is about 30 to 70; "too loud" above 100, "too quiet" below 15). Dire Wolf's full output is behind **Show full Dire Wolf log**. Release APK 317,509 bytes unsigned.

First radio test (2026-10-07, receive only): FT-817 in PKT mode on 144.390 MHz, Digirig audio into the phone, PTT None. Decoded APRS packets via the VA3KMS digipeater (GBTWP-2 weather station, VA3TM, KA8POG), each at audio level 200: too loud, still decoded. Found: **Sound output (transmit)** "Phone's default" is the Digirig while it is plugged in (Android's default output); choose the built-in speaker to keep sound off the radio.

## Input level (2026-10-07)

Setting **Input level, % (5 to 400; default 100)**: the app passes it to Dire Wolf as the environment variable `DIREWOLF_INPUT_PERCENT`, and `port/audio_aaudio.c` scales the sound card samples by it before Dire Wolf decodes them (Dire Wolf's own files are unchanged). For radios whose fixed receive level is too loud or too quiet: the FT-817's DATA socket gives a fixed level with no menu to change it.

Checked on the air (2 m APRS, FT-817, Digirig): at 100 % every packet read audio level 200 with Dire Wolf's "too high" warning (16 packets, run `aprs-rx-01`); at 25 % the same stations read level 50, no warning, still decoding (VE3NOZ-1, VA3KMS, VA3TM, K8JJT-9). Every packet reading exactly 200 at 100 % suggests the sound may already be at full scale in the Digirig's input; the level number is now right, and decoding continued. Release APK 318,225 bytes unsigned.

## Waterfall (2026-10-10)

The main screen shows a **Waterfall** under **Start** / **Stop**: the app passes `DIREWOLF_WATERFALL_PORT` (8009), and `port/audio_aaudio.c` sends the first sound card's received samples (after the input level) as 16-bit UDP datagrams to 127.0.0.1 on that port; `WaterfallFeed.java` receives them while the screen is visible and `WaterfallView.java` draws 0 to 3,000 Hz with the tones of the chosen speed marked (1200 baud: 1,200 and 2,200 Hz; 300 baud: 1,600 and 1,800 Hz; 9600: none). Dire Wolf's own files are unchanged.

Checked on the phone (SM-G981W): the room sound showed while the modem ran with the phone's microphone. Release APK 322,721 bytes unsigned.
