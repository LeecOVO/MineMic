#pragma once
#include "common_audio/resampler/sinc_resampler.h"
#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>

// Two channels share the same input cursor and slowly varying ratio. All storage
// is bounded; audio is pulled at the microphone cadence, not UDP arrival cadence.
class ReferenceResampler {
    static constexpr std::size_t Capacity = 8192, Request = 64;
    struct Reader final : webrtc::SincResamplerCallback {
        ReferenceResampler& owner;
        std::size_t channel;
        std::uint64_t cursor = 0;
        Reader(ReferenceResampler& owner, std::size_t channel) : owner(owner), channel(channel) {}
        void Run(std::size_t frames, float* destination) override {
            for (std::size_t i = 0; i < frames; ++i) destination[i] = owner.samples_[channel][cursor++ % Capacity];
        }
    } left_{*this, 0}, right_{*this, 1};
    webrtc::SincResampler leftFilter_{1., Request, &left_}, rightFilter_{1., Request, &right_};
    std::array<std::array<float, Capacity>, 2> samples_{};
    struct Missing { std::uint64_t begin = 0, end = 0; };
    std::array<Missing, 32> missing_{};
    std::size_t missingCount_ = 0;
    std::uint64_t written_ = 0, lastRatioAt_ = 0;
    double outputPosition_ = 0, ratio_ = 1.;
public:
    bool push(const std::uint8_t* pcm, bool valid) {
        if (!pcm || written_ - std::min(left_.cursor, right_.cursor) + 480 > Capacity) return false;
        if (!valid) {
            if (missingCount_ == missing_.size()) return false;
            missing_[missingCount_++] = {written_, written_ + 480};
        }
        for (std::size_t i = 0; i < 480; ++i) for (std::size_t channel = 0; channel < 2; ++channel) {
            const auto at = 4*i + 2*channel;
            const auto value = static_cast<std::int16_t>(pcm[at] | (std::uint16_t(pcm[at+1]) << 8));
            samples_[channel][(written_ + i) % Capacity] = value;
        }
        written_ += 480;
        return true;
    }
    // 0: real reference, 1: insufficient input, 2: concealment touched this
    // output's filter window. The latter may advance AEC but must not be heard.
    int read(double requestedRatio, std::uint8_t* pcm) {
        if (!pcm || !std::isfinite(requestedRatio) || requestedRatio < .998 || requestedRatio > 1.002) return -1;
        if (written_ - lastRatioAt_ >= 4800 && std::abs(requestedRatio - ratio_) >= .000001) {
            ratio_ = requestedRatio; lastRatioAt_ = written_;
            leftFilter_.SetRatio(ratio_); rightFilter_.SetRatio(ratio_);
        }
        // Sinc needs 16 samples of lookahead and reads whole 64-sample chunks.
        const auto end = outputPosition_ + 480 * ratio_;
        const auto required = static_cast<std::uint64_t>(std::ceil((end + 16) / Request)) * Request;
        if (written_ < required) return 1;
        bool valid = true;
        for (std::size_t i = 0; i < missingCount_; ++i)
            if (missing_[i].begin < end + 16 && static_cast<double>(missing_[i].end) > outputPosition_ - 16) valid = false;
        std::array<float, 480> left{}, right{};
        leftFilter_.Resample(480, left.data()); rightFilter_.Resample(480, right.data());
        for (std::size_t i = 0; i < 480; ++i) for (std::size_t channel = 0; channel < 2; ++channel) {
            const auto value = static_cast<std::int16_t>(std::lround(std::clamp(channel ? right[i] : left[i], -32768.f, 32767.f)));
            pcm[4*i + 2*channel] = static_cast<std::uint8_t>(value);
            pcm[4*i + 2*channel + 1] = static_cast<std::uint8_t>(std::uint16_t(value) >> 8);
        }
        outputPosition_ = end;
        auto keep = std::remove_if(missing_.begin(), missing_.begin() + missingCount_, [&](const Missing& gap) {
            return static_cast<double>(gap.end) <= outputPosition_ - 16;
        });
        missingCount_ = static_cast<std::size_t>(keep - missing_.begin());
        return valid ? 0 : 2;
    }
};
