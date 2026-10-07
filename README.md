# Dire Wolf Modem for Android

Android build of the Dire Wolf packet modem and TNC, version 1.8.1 (git a231971, November 2025). Separate from the FieldMail app: FieldMail talks to this modem only over Dire Wolf's standard network ports on the phone, so FieldMail contains no Dire Wolf code.

Status 2026-10-07: steps B1 (built and checked), B2 (packets both ways through the air) and B3 (the app) done. FieldMail's connection (B4), PTT (B5) and GPS (B6) are not built yet. The plan (steps B1 to B6) is in FieldMail's `PACKET-STATUS.md`.

## Source

`upstream/` is a plain copy of https://github.com/wb2osz/direwolf at tag `1.8.1`, commit `a231971a652bfb574a4bae9a5d875fbce53d2267`, without its git history. The only changes to upstream files are in `patches/0001-android-loopback.patch` (see below). The Android build replaces one file and fills header gaps from `port/`.

To move to a newer Dire Wolf release: replace `upstream/` with a copy of the new release, apply `patches/0001-android-loopback.patch`, check `CMakeLists.txt` against the new `upstream/src/CMakeLists.txt` source lists, rebuild, and repeat the checks below.

## Licence

Dire Wolf is GPL-2.0-or-later (`upstream/LICENSE`, copied to `LICENSE`). Everything in this folder is GPL-2.0-or-later. Anyone given a copy of this modem must also be offered its full source, including the files in `port/`.

## What is here

| Path | What it is |
|------|------------|
| `upstream/` | Copy of Dire Wolf 1.8.1 (see Source above) with the patch below applied |
| `patches/0001-android-loopback.patch` | The only change to Dire Wolf's own files: on Android, the KISS and AGW ports listen on 127.0.0.1 only (`upstream/src/kissnet.c`, `upstream/src/server.c`, inside `#if __ANDROID__`), so other devices on the phone's network cannot connect and make it transmit |
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

Always written: `ARATE 48000`, `ACHANNELS 1`, `CHANNEL 0`. No PTT yet.

Checks on save: ports 1024 to 65535 and different from each other, callsign up to 6 letters and digits with an optional -SSID 0 to 15, times 0 to 2550 ms, persistence 0 to 255. A sound device is saved by ID and name; at Start, a device that is no longer present is looked up by name (a re-plugged USB sound card gets a new ID), else the phone's default is used and the log says so.

Checked on the phone 2026-10-07: **Start** asks for the microphone (and notification) permission, then shows "Running — waiting for a program on port 8101" and the "Dire Wolf modem running" notification; `/proc/net/tcp` shows only 127.0.0.1:8100 and 127.0.0.1:8101 listening; a connection from the phone itself is "Attached to KISS TCP client"; a connection from the PC over Wi-Fi to the phone's address, port 8101, is refused; **Stop** ends the program, closes both ports, removes the notification and shows "Stopped". Settings: KISS port 8111 saved and used (Dire Wolf "Ready to accept KISS TCP client application 0 on port 8111", 127.0.0.1:8111 listening, settings file matches the screen); KISS port 8100 refused with "Not saved: KISS port and AGW port must be different."; back to 8101; the **Sound input** list offers "Phone's default" and the two built-in microphones (devices 19 and 21).
