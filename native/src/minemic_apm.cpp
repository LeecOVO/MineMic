#include "minemic_apm.h"
#include "api/audio/audio_processing.h"
#include "rnnoise.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <new>

namespace {
struct Processor {
    rtc::scoped_refptr<webrtc::AudioProcessing> echo, noise;
    const webrtc::StreamConfig mono{48000, 1}, stereo{48000, 2};
    bool desk = false;
    DenoiseState* neural = nullptr;
    ~Processor() { if (neural) rnnoise_destroy(neural); }
    static rtc::scoped_refptr<webrtc::AudioProcessing> make(bool aec, bool ns) {
        auto apm = webrtc::AudioProcessingBuilder().Create();
        if (!apm) return nullptr;
        webrtc::AudioProcessing::Config config;
        config.pipeline.multi_channel_render = true;
        config.echo_canceller.enabled = aec;
        config.noise_suppression.enabled = ns;
        config.noise_suppression.level = webrtc::AudioProcessing::Config::NoiseSuppression::kModerate;
        config.gain_controller1.enabled = false;
        config.gain_controller2.enabled = false;
        apm->ApplyConfig(config);
        return apm;
    }
};
// Avoid alignment assumptions across JNI/Kotlin byte arrays.
template<size_t N> std::array<int16_t, N> decode(const uint8_t* pcm) {
    std::array<int16_t, N> samples{};
    for (size_t i = 0; i < N; ++i) samples[i] = static_cast<int16_t>(pcm[2*i] | (uint16_t(pcm[2*i+1]) << 8));
    return samples;
}
}
extern "C" void* mm_apm_create() { try { return new Processor(); } catch (...) { return nullptr; } }
extern "C" void mm_apm_destroy(void* handle) { delete static_cast<Processor*>(handle); }
extern "C" void mm_apm_reset_echo(void* handle) { if (handle) static_cast<Processor*>(handle)->echo = nullptr; }
extern "C" void mm_apm_set_desk_noise(void* handle, int enabled) {
    if (!handle) return;
    auto& p = *static_cast<Processor*>(handle);
    if (p.desk == bool(enabled)) return;
    p.desk = bool(enabled);
    // A changed profile starts with clean adaptation, without resetting AEC.
    p.noise = nullptr;
    if (p.neural) { rnnoise_destroy(p.neural); p.neural = nullptr; }
}
extern "C" int mm_apm_render(void* handle, const uint8_t* pcm) {
    if (!handle || !pcm) return -1;
    try {
        auto& p = *static_cast<Processor*>(handle);
        if (!p.echo) p.echo = Processor::make(true, false);
        if (!p.echo) return -2;
        auto input = decode<960>(pcm);
        std::array<int16_t, 960> output{};
        return p.echo->ProcessReverseStream(input.data(), p.stereo, p.stereo, output.data());
    } catch (...) { return -3; }
}
extern "C" int mm_apm_capture(void* handle, const uint8_t* input, uint8_t* output, int ns, int aec) {
    if (!handle || !input || !output) return -1;
    try {
        auto& p = *static_cast<Processor*>(handle);
        auto samples = decode<480>(input);
        if (aec) {
            if (!p.echo) return -2; // Never pretend AEC works without a reference.
            p.echo->set_stream_delay_ms(0); // Streams are aligned to the phone capture clock.
            int status = p.echo->ProcessStream(samples.data(), p.mono, p.mono, samples.data());
            if (status) return status;
        }
        if (ns && p.desk) {
            if (!p.neural) p.neural = rnnoise_create(nullptr);
            if (!p.neural) return -2;
            std::array<float, 480> in{}, out{};
            for (size_t i = 0; i < samples.size(); ++i) in[i] = samples[i];
            // RNNoise consumes PCM16-range floats, not normalized [-1, 1].
            rnnoise_process_frame(p.neural, out.data(), in.data());
            for (size_t i = 0; i < samples.size(); ++i) {
                if (!std::isfinite(out[i])) return -3;
                samples[i] = static_cast<int16_t>(std::lround(std::clamp(out[i], -32768.f, 32767.f)));
            }
        } else if (ns) {
            if (!p.noise) p.noise = Processor::make(false, true);
            if (!p.noise) return -2;
            int status = p.noise->ProcessStream(samples.data(), p.mono, p.mono, samples.data());
            if (status) return status;
        }
        for (size_t i = 0; i < samples.size(); ++i) {
            output[2*i] = static_cast<uint8_t>(samples[i]);
            output[2*i+1] = static_cast<uint8_t>(uint16_t(samples[i]) >> 8);
        }
        return 0;
    } catch (...) { return -3; }
}
