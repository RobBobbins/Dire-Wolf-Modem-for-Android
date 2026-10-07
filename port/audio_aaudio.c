//
//    Android sound for Dire Wolf: the audio_* interface of audio.c on AAudio.
//
//    Part of the Android build of Dire Wolf (Dire Wolf Modem). Dire Wolf is
//    Copyright (C) 2011-2025 John Langner, WB2OSZ. This file is
//    Copyright (C) 2026 the Dire Wolf Modem project.
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 2 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//    GNU General Public License for more details.
//

/*------------------------------------------------------------------
 * Replaces audio.c on Android. Same functions, same byte-at-a-time
 * contract (the caller handles channels and sample size):
 *
 *	audio_open	audio_get	audio_put
 *	audio_flush	audio_wait	audio_close
 *
 * Sound card: AAudio streams in blocking mode, 16-bit PCM only.
 *	ADEVICE "default" (or empty) uses Android's default input and output;
 *	a number selects that Android audio device ID (AudioDeviceInfo.getId()).
 * Input "stdin" / "-" and "udp:port" behave as in audio.c.
 *---------------------------------------------------------------*/

#include "direwolf.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>
#include <assert.h>
#include <errno.h>
#include <time.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <aaudio/AAudio.h>

#include "audio.h"
#include "audio_stats.h"
#include "textcolor.h"
#include "dtime_now.h"

static struct audio_s *save_audio_config_p;

static struct adev_s {
	AAudioStream *in_stream;
	AAudioStream *out_stream;
	int bytes_per_frame;		/* All channels of one sample, e.g. 2 for mono 16 bit. */

	int inbuf_size_in_bytes;
	unsigned char *inbuf_ptr;
	int inbuf_len;
	int inbuf_next;

	int outbuf_size_in_bytes;
	unsigned char *outbuf_ptr;
	int outbuf_len;

	enum audio_in_type_e g_audio_in_type;
	int udp_sock;
} adev[MAX_ADEVS];

/* Buffer time, ms, for each read and write; audio.c uses 10. */
#define ONE_BUF_TIME 10

/* Longest wait for one blocking read or write. */
#define AAUDIO_TIMEOUT_NANOS (2000LL * 1000000LL)


static int device_id (const char *name)
{
	if (name == NULL || *name == '\0' || strcasecmp(name, "default") == 0) return AAUDIO_UNSPECIFIED;
	char *end;
	long id = strtol(name, &end, 10);
	if (*end != '\0' || id <= 0) {
	  text_color_set(DW_COLOR_ERROR);
	  dw_printf ("Audio device \"%s\" is not \"default\" or an Android audio device number; using the default.\n", name);
	  return AAUDIO_UNSPECIFIED;
	}
	return (int)id;
}


static AAudioStream *open_stream (int a, struct audio_s *pa, const char *name, aaudio_direction_t direction)
{
	AAudioStreamBuilder *b;
	AAudioStream *s = NULL;
	aaudio_result_t r = AAudio_createStreamBuilder(&b);
	if (r != AAUDIO_OK) {
	  text_color_set(DW_COLOR_ERROR);
	  dw_printf ("AAudio: cannot create a stream builder: %s\n", AAudio_convertResultToText(r));
	  return NULL;
	}
	AAudioStreamBuilder_setDirection(b, direction);
	AAudioStreamBuilder_setDeviceId(b, device_id(name));
	AAudioStreamBuilder_setSampleRate(b, pa->adev[a].samples_per_sec);
	AAudioStreamBuilder_setChannelCount(b, pa->adev[a].num_channels);
	AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
	AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
	AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_NONE);
	if (direction == AAUDIO_DIRECTION_INPUT) {
#if __ANDROID_API__ >= 28
	  AAudioStreamBuilder_setInputPreset(b, AAUDIO_INPUT_PRESET_GENERIC);
#endif
	}
	else {
#if __ANDROID_API__ >= 28
	  AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
	  AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
#endif
	}
	r = AAudioStreamBuilder_openStream(b, &s);
	AAudioStreamBuilder_delete(b);
	const char *dir = direction == AAUDIO_DIRECTION_INPUT ? "input" : "output";
	if (r != AAUDIO_OK) {
	  text_color_set(DW_COLOR_ERROR);
	  dw_printf ("Could not open audio device %s for %s: %s\n", name, dir, AAudio_convertResultToText(r));
	  return NULL;
	}
	if (AAudioStream_getSampleRate(s) != pa->adev[a].samples_per_sec ||
	    AAudioStream_getChannelCount(s) != pa->adev[a].num_channels ||
	    AAudioStream_getFormat(s) != AAUDIO_FORMAT_PCM_I16) {
	  text_color_set(DW_COLOR_ERROR);
	  dw_printf ("Audio device %s for %s gave %d samples per second, %d channels, format %d; wanted %d, %d, 16-bit.\n",
		name, dir, AAudioStream_getSampleRate(s), AAudioStream_getChannelCount(s), AAudioStream_getFormat(s),
		pa->adev[a].samples_per_sec, pa->adev[a].num_channels);
	  AAudioStream_close(s);
	  return NULL;
	}
	r = AAudioStream_requestStart(s);
	if (r != AAUDIO_OK) {
	  text_color_set(DW_COLOR_ERROR);
	  dw_printf ("Could not start audio device %s for %s: %s\n", name, dir, AAudio_convertResultToText(r));
	  AAudioStream_close(s);
	  return NULL;
	}
	text_color_set(DW_COLOR_INFO);
	dw_printf ("Audio %s: AAudio device %d, %d samples per second, %d channel(s), 16 bit, burst %d frames.\n",
		dir, AAudioStream_getDeviceId(s), AAudioStream_getSampleRate(s), AAudioStream_getChannelCount(s),
		AAudioStream_getFramesPerBurst(s));
	return s;
}


int audio_open (struct audio_s *pa)
{
	int a, chan;
	save_audio_config_p = pa;
	memset (adev, 0, sizeof(adev));
	for (a = 0; a < MAX_ADEVS; a++) adev[a].udp_sock = -1;

	for (a = 0; a < MAX_ADEVS; a++) {
	  if (pa->adev[a].num_channels == 0) pa->adev[a].num_channels = DEFAULT_NUM_CHANNELS;
	  if (pa->adev[a].samples_per_sec == 0) pa->adev[a].samples_per_sec = DEFAULT_SAMPLES_PER_SEC;
	  if (pa->adev[a].bits_per_sample == 0) pa->adev[a].bits_per_sample = DEFAULT_BITS_PER_SAMPLE;
	  for (chan = 0; chan < MAX_RADIO_CHANS; chan++) {
	    if (pa->achan[chan].mark_freq == 0) pa->achan[chan].mark_freq = DEFAULT_MARK_FREQ;
	    if (pa->achan[chan].space_freq == 0) pa->achan[chan].space_freq = DEFAULT_SPACE_FREQ;
	    if (pa->achan[chan].baud == 0) pa->achan[chan].baud = DEFAULT_BAUD;
	    if (pa->achan[chan].num_subchan == 0) pa->achan[chan].num_subchan = 1;
	  }
	}

	for (a = 0; a < MAX_ADEVS; a++) {
	  if (!pa->adev[a].defined) continue;

	  if (pa->adev[a].bits_per_sample != 16) {
	    text_color_set(DW_COLOR_ERROR);
	    dw_printf ("Android sound supports 16 bits per sample only (ARATE / bits setting for device %d).\n", a);
	    return (-1);
	  }
	  adev[a].bytes_per_frame = pa->adev[a].num_channels * 2;
	  int buf_bytes = pa->adev[a].samples_per_sec * ONE_BUF_TIME / 1000 * adev[a].bytes_per_frame;

	  adev[a].g_audio_in_type = AUDIO_IN_TYPE_SOUNDCARD;
	  if (strcasecmp(pa->adev[a].adevice_in, "stdin") == 0 || strcmp(pa->adev[a].adevice_in, "-") == 0) {
	    adev[a].g_audio_in_type = AUDIO_IN_TYPE_STDIN;
	    strlcpy (pa->adev[a].adevice_in, "stdin", sizeof(pa->adev[a].adevice_in));
	  }
	  if (strncasecmp(pa->adev[a].adevice_in, "udp:", 4) == 0) {
	    adev[a].g_audio_in_type = AUDIO_IN_TYPE_SDR_UDP;
	    if (strcasecmp(pa->adev[a].adevice_in, "udp:") == 0)
	      snprintf (pa->adev[a].adevice_in, sizeof(pa->adev[a].adevice_in), "udp:%d", DEFAULT_UDP_AUDIO_PORT);
	  }

	  text_color_set(DW_COLOR_INFO);
	  dw_printf ("Audio input device for receive: %s (channel %d)\n", pa->adev[a].adevice_in, ADEVFIRSTCHAN(a));
	  dw_printf ("Audio out device for transmit: %s (channel %d)\n", pa->adev[a].adevice_out, ADEVFIRSTCHAN(a));

	  switch (adev[a].g_audio_in_type) {
	    case AUDIO_IN_TYPE_SOUNDCARD:
	      adev[a].in_stream = open_stream(a, pa, pa->adev[a].adevice_in, AAUDIO_DIRECTION_INPUT);
	      if (adev[a].in_stream == NULL) return (-1);
	      adev[a].inbuf_size_in_bytes = buf_bytes;
	      break;
	    case AUDIO_IN_TYPE_SDR_UDP: {
	      struct sockaddr_in si_me;
	      if ((adev[a].udp_sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)) == -1) {
	        text_color_set(DW_COLOR_ERROR);
	        dw_printf ("Couldn't create socket, errno %d\n", errno);
	        return (-1);
	      }
	      memset ((char *)&si_me, 0, sizeof(si_me));
	      si_me.sin_family = AF_INET;
	      si_me.sin_port = htons((short)atoi(pa->adev[a].adevice_in + 4));
	      si_me.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
	      if (bind(adev[a].udp_sock, (const struct sockaddr *)&si_me, sizeof(si_me)) == -1) {
	        text_color_set(DW_COLOR_ERROR);
	        dw_printf ("Couldn't bind socket, errno %d\n", errno);
	        return (-1);
	      }
	      adev[a].inbuf_size_in_bytes = SDR_UDP_BUF_MAXLEN;
	      break;
	    }
	    case AUDIO_IN_TYPE_STDIN:
	      adev[a].inbuf_size_in_bytes = 1024;
	      break;
	    default:
	      text_color_set(DW_COLOR_ERROR);
	      dw_printf ("Internal error, invalid audio_in_type\n");
	      return (-1);
	  }

	  adev[a].out_stream = open_stream(a, pa, pa->adev[a].adevice_out, AAUDIO_DIRECTION_OUTPUT);
	  if (adev[a].out_stream == NULL) return (-1);
	  adev[a].outbuf_size_in_bytes = buf_bytes;

	  adev[a].inbuf_ptr = malloc(adev[a].inbuf_size_in_bytes);
	  assert (adev[a].inbuf_ptr != NULL);
	  adev[a].outbuf_ptr = malloc(adev[a].outbuf_size_in_bytes);
	  assert (adev[a].outbuf_ptr != NULL);
	  adev[a].inbuf_len = adev[a].inbuf_next = adev[a].outbuf_len = 0;
	}
	return (0);
}


__attribute__((hot))
int audio_get (int a)
{
	while (adev[a].inbuf_next >= adev[a].inbuf_len) {
	  int res;
	  switch (adev[a].g_audio_in_type) {
	    case AUDIO_IN_TYPE_SOUNDCARD: {
	      int frames = adev[a].inbuf_size_in_bytes / adev[a].bytes_per_frame;
	      aaudio_result_t r = AAudioStream_read(adev[a].in_stream, adev[a].inbuf_ptr, frames, AAUDIO_TIMEOUT_NANOS);
	      if (r < 0) {
	        text_color_set(DW_COLOR_ERROR);
	        dw_printf ("Can't read from audio device: %s\n", AAudio_convertResultToText(r));
	        adev[a].inbuf_len = adev[a].inbuf_next = 0;
	        audio_stats (a, save_audio_config_p->adev[a].num_channels, 0, save_audio_config_p->statistics_interval);
	        return (-1);
	      }
	      res = r * adev[a].bytes_per_frame;
	      break;
	    }
	    case AUDIO_IN_TYPE_SDR_UDP:
	      res = recv(adev[a].udp_sock, adev[a].inbuf_ptr, adev[a].inbuf_size_in_bytes, 0);
	      if (res < 0) {
	        text_color_set(DW_COLOR_ERROR);
	        dw_printf ("Can't read from udp socket, res=%d", res);
	        adev[a].inbuf_len = adev[a].inbuf_next = 0;
	        audio_stats (a, save_audio_config_p->adev[a].num_channels, 0, save_audio_config_p->statistics_interval);
	        return (-1);
	      }
	      break;
	    case AUDIO_IN_TYPE_STDIN:
	    default:
	      res = read(STDIN_FILENO, adev[a].inbuf_ptr, (size_t)adev[a].inbuf_size_in_bytes);
	      if (res <= 0) {
	        text_color_set(DW_COLOR_INFO);
	        dw_printf ("\nEnd of file on stdin.  Exiting.\n");
	        exit (0);
	      }
	      break;
	  }
	  adev[a].inbuf_len = res;
	  adev[a].inbuf_next = 0;
	  audio_stats (a, save_audio_config_p->adev[a].num_channels,
		res / adev[a].bytes_per_frame, save_audio_config_p->statistics_interval);
	}
	return adev[a].inbuf_ptr[adev[a].inbuf_next++];
}


int audio_put (int a, int c)
{
	assert (adev[a].outbuf_len < adev[a].outbuf_size_in_bytes);
	adev[a].outbuf_ptr[adev[a].outbuf_len++] = c;
	if (adev[a].outbuf_len == adev[a].outbuf_size_in_bytes) return (audio_flush(a));
	return (0);
}


int audio_flush (int a)
{
	int frames = adev[a].outbuf_len / adev[a].bytes_per_frame;
	int done = 0;
	while (done < frames) {
	  aaudio_result_t r = AAudioStream_write(adev[a].out_stream,
		adev[a].outbuf_ptr + done * adev[a].bytes_per_frame, frames - done, AAUDIO_TIMEOUT_NANOS);
	  if (r < 0) {
	    text_color_set(DW_COLOR_ERROR);
	    dw_printf ("Audio write error: %s\n", AAudio_convertResultToText(r));
	    adev[a].outbuf_len = 0;
	    return (-1);
	  }
	  if (r == 0) {
	    text_color_set(DW_COLOR_ERROR);
	    dw_printf ("Audio write timed out; %d of %d frames written.\n", done, frames);
	    adev[a].outbuf_len = 0;
	    return (-1);
	  }
	  done += r;
	}
	adev[a].outbuf_len = 0;
	return (0);
}


/* Flush, then wait until the output stream has played everything written (or 5 s). */
void audio_wait (int a)
{
	audio_flush (a);
	if (adev[a].out_stream == NULL) return;
	double limit = dtime_monotonic() + 5.0;
	while (AAudioStream_getFramesRead(adev[a].out_stream) < AAudioStream_getFramesWritten(adev[a].out_stream) && dtime_monotonic() < limit) {
	  usleep (5000);
	}
}


int audio_close (void)
{
	int a;
	for (a = 0; a < MAX_ADEVS; a++) {
	  if (adev[a].out_stream != NULL) {
	    audio_wait (a);
	    AAudioStream_requestStop(adev[a].out_stream);
	    AAudioStream_close(adev[a].out_stream);
	    adev[a].out_stream = NULL;
	  }
	  if (adev[a].in_stream != NULL) {
	    AAudioStream_requestStop(adev[a].in_stream);
	    AAudioStream_close(adev[a].in_stream);
	    adev[a].in_stream = NULL;
	  }
	  if (adev[a].udp_sock >= 0) { close(adev[a].udp_sock); adev[a].udp_sock = -1; }
	  free (adev[a].inbuf_ptr);
	  free (adev[a].outbuf_ptr);
	  adev[a].inbuf_ptr = adev[a].outbuf_ptr = NULL;
	  adev[a].inbuf_size_in_bytes = adev[a].outbuf_size_in_bytes = 0;
	  adev[a].inbuf_len = adev[a].inbuf_next = adev[a].outbuf_len = 0;
	}
	return (0);
}
