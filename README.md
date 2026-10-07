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

Build: `gradlew.bat assembleDebug assembleRelease` in this folder (it builds and strips the program first). Release APK 277,320 bytes unsigned.

Settings the service writes to `direwolf.conf` in the app's files: `ADEVICE default`, `ARATE 48000`, `ACHANNELS 1`, `MYCALL NOCALL` (KISS programs supply their own callsigns), `MODEM 1200`, `AGWPORT 8100`, `KISSPORT 0` (removes Dire Wolf's default KISS port 8001), `KISSPORT 8101`. No PTT yet.

Checked on the phone 2026-10-07: **Start** asks for the microphone (and notification) permission, then shows "Running — waiting for a program on port 8101" and the "Dire Wolf modem running" notification; `/proc/net/tcp` shows only 127.0.0.1:8100 and 127.0.0.1:8101 listening; a connection from the phone itself is "Attached to KISS TCP client"; a connection from the PC over Wi-Fi to the phone's address, port 8101, is refused; **Stop** ends the program, closes both ports, removes the notification and shows "Stopped".
