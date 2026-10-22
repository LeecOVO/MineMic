#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
void* mm_apm_create(void);
void mm_apm_destroy(void* handle);
void mm_apm_reset_echo(void* handle);
/* Switch the optional speech-focused RNNoise profile; independent of AEC. */
void mm_apm_set_desk_noise(void* handle, int enabled);
/* One interleaved stereo, 480-frame, PCM16LE playback reference. */
int mm_apm_render(void* handle, const uint8_t* stereo_pcm);
/* Mono 480-frame PCM16LE. ns/aec are independent. 0=success. */
int mm_apm_capture(void* handle, const uint8_t* input, uint8_t* output, int ns, int aec);
#ifdef __cplusplus
}
#endif
