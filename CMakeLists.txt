# Android (NDK) build of the Dire Wolf packet modem command-line programs.
# Dire Wolf is GPL-2.0-or-later (see upstream/LICENSE); this build file is too.
# Source lists follow upstream/src/CMakeLists.txt of Dire Wolf 1.8.1 (git a231971).
#
# Android choices: AAudio is the only sound system (port/audio_aaudio.c replaces
# upstream/src/audio.c); no gpsd, hamlib, libgpiod, udev/CM108 or DNS-SD.
# Android's C library has strlcpy and strlcat, so external/misc is not needed.

cmake_minimum_required(VERSION 3.22)
project(direwolf_android C)

set(UP ${CMAKE_CURRENT_SOURCE_DIR}/upstream)
set(SRC ${UP}/src)
set(PORT ${CMAKE_CURRENT_SOURCE_DIR}/port)

set(DIREWOLF_SRCS
    direwolf.c ais.c aprs_tt.c audio_stats.c ax25_link.c ax25_pad.c ax25_pad2.c
    beacon.c config.c decode_aprs.c deviceid.c dedupe.c demod_9600.c demod_afsk.c
    demod_psk.c demod.c digipeater.c cdigipeater.c dlq.c dsp.c dtime_now.c dtmf.c
    dwgps.c dwsock.c encode_aprs.c fcs_calc.c fx25_encode.c fx25_extract.c fx25_init.c
    fx25_rec.c fx25_send.c fx25_auto.c gen_tone.c hdlc_rec.c hdlc_rec2.c hdlc_send.c
    igate.c il2p_codec.c il2p_scramble.c il2p_rec.c il2p_payload.c il2p_init.c
    il2p_header.c il2p_send.c kiss_frame.c kiss.c kissserial.c kissnet.c latlong.c
    log.c morse.c multi_modem.c waypoint.c nettnc.c serial_port.c pfilter.c ptt.c
    recv.c rrbb.c server.c symbols.c telemetry.c textcolor.c tq.c tt_text.c tt_user.c
    xid.c xmit.c dwgpsnmea.c dwgpsd.c mheard.c gpio_common.c)
list(TRANSFORM DIREWOLF_SRCS PREPEND ${SRC}/)

set(ATEST_SRCS
    atest.c ais.c demod.c demod_afsk.c demod_psk.c demod_9600.c dsp.c fx25_extract.c
    fx25_encode.c fx25_init.c fx25_rec.c hdlc_rec.c hdlc_rec2.c il2p_codec.c
    il2p_scramble.c il2p_rec.c il2p_payload.c il2p_init.c il2p_header.c multi_modem.c
    rrbb.c fcs_calc.c ax25_pad.c ax25_pad2.c decode_aprs.c deviceid.c dwgpsnmea.c
    dwgps.c dwgpsd.c serial_port.c telemetry.c dtime_now.c latlong.c symbols.c
    tt_text.c textcolor.c)
list(TRANSFORM ATEST_SRCS PREPEND ${SRC}/)

set(GEOTRANZ_SRCS error_string.c mgrs.c polarst.c tranmerc.c ups.c usng.c utm.c)
list(TRANSFORM GEOTRANZ_SRCS PREPEND ${UP}/external/geotranz/)
add_library(geotranz STATIC ${GEOTRANZ_SRCS})
target_include_directories(geotranz PRIVATE ${UP}/external/geotranz)

# Version and name strings: port/android_config.h, included into every file.
set(DW_DEFINES _GNU_SOURCE)
set(DW_OPTIONS -include ${PORT}/android_config.h -O2 -std=gnu11 -pthread -ffast-math -Wno-unused -Wno-deprecated-declarations)

add_executable(direwolf ${DIREWOLF_SRCS} ${PORT}/audio_aaudio.c)
target_include_directories(direwolf PRIVATE ${PORT}/include ${SRC} ${UP}/external/geotranz)
target_compile_definitions(direwolf PRIVATE ${DW_DEFINES})
target_compile_options(direwolf PRIVATE ${DW_OPTIONS})
target_link_libraries(direwolf PRIVATE geotranz aaudio log m)

add_executable(atest ${ATEST_SRCS})
target_include_directories(atest PRIVATE ${PORT}/include ${SRC} ${UP}/external/geotranz)
target_compile_definitions(atest PRIVATE ${DW_DEFINES})
target_compile_options(atest PRIVATE ${DW_OPTIONS})
target_link_libraries(atest PRIVATE m)
