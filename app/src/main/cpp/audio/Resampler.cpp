#include "audio/Resampler.h"

namespace audio {

namespace {
constexpr int32_t kPhases = 256;
constexpr double kKaiserBeta = 10.06;
constexpr int32_t kBaseHalf = 32;
} // namespace

double PolyphaseResampler::besselI0(double x) {
    double sum = 1.0;
    double term = 1.0;
    const double y = x * x / 4.0;
    for (int32_t i = 1; i < 60; ++i) {
        term *= y / (static_cast<double>(i) * static_cast<double>(i));
        sum += term;
        if (term < sum * 1e-14) break;
    }
    return sum;
}

void PolyphaseResampler::configure(int32_t inRate, int32_t outRate, int32_t channels) {
    channels_ = channels > 0 ? channels : 2;
    step_ = (inRate > 0 && outRate > 0 && inRate != outRate)
                    ? static_cast<double>(inRate) / static_cast<double>(outRate)
                    : 1.0;
    half_ = static_cast<int32_t>(std::ceil(kBaseHalf * std::max(1.0, step_)));
    taps_ = half_ * 2;
    rebuildKernel();
    reset();
}

void PolyphaseResampler::rebuildKernel() {
    kernel_.assign(static_cast<size_t>(kPhases) * static_cast<size_t>(taps_), 0.0f);
    // Al bajar de tasa (step > 1) hay que cortar por debajo del Nyquist de SALIDA; al
    // subir (step < 1) el Nyquist que manda es el de ENTRADA (filtro plano hasta él).
    const double fc = step_ > 1.0 ? 0.5 * 0.96 / step_ : 0.5;
    const double norm = besselI0(kKaiserBeta);
    for (int32_t ph = 0; ph < kPhases; ++ph) {
        const double frac = static_cast<double>(ph) / static_cast<double>(kPhases);
        double sum = 0.0;
        for (int32_t t = 0; t < taps_; ++t) {
            const double x = static_cast<double>(t - half_ + 1) - frac;
            const double arg = 2.0 * fc * x;
            const double sinc =
                    std::fabs(arg) < 1e-9 ? 1.0 : std::sin(M_PI * arg) / (M_PI * arg);
            const double r = x / static_cast<double>(half_);
            const double window = std::fabs(r) >= 1.0
                    ? 0.0
                    : besselI0(kKaiserBeta * std::sqrt(1.0 - r * r)) / norm;
            const double v = 2.0 * fc * sinc * window;
            kernel_[static_cast<size_t>(ph) * taps_ + t] = static_cast<float>(v);
            sum += v;
        }
        if (std::fabs(sum) > 1e-12) {
            for (int32_t t = 0; t < taps_; ++t) {
                const size_t idx = static_cast<size_t>(ph) * taps_ + t;
                kernel_[idx] = static_cast<float>(kernel_[idx] / sum);
            }
        }
    }
}

void PolyphaseResampler::reset() {
    historyFrames_ = 0;
    pos_ = std::max(0, half_ - 1);
    work_.assign(static_cast<size_t>(std::max(taps_ * 2, 64)) * channels_, 0.0f);
}

int32_t PolyphaseResampler::process(const float* in, int32_t inFrames, float* out,
                                    int32_t maxOutFrames) {
    if (inFrames <= 0 || maxOutFrames <= 0) return 0;

    if (bypass()) {
        const int32_t n = std::min(inFrames, maxOutFrames);
        std::memcpy(out, in, sizeof(float) * static_cast<size_t>(n) * channels_);
        return n;
    }

    const int32_t histSamples = historyFrames_ * channels_;
    const int32_t inSamples = inFrames * channels_;
    const size_t workSamples = static_cast<size_t>(histSamples + inSamples);
    if (work_.size() < workSamples) work_.resize(workSamples, 0.0f);
    std::memcpy(work_.data() + histSamples, in, sizeof(float) * static_cast<size_t>(inSamples));

    const int32_t workFrames = historyFrames_ + inFrames;
    int32_t outFrames = 0;

    while (outFrames < maxOutFrames) {
        const int32_t base = static_cast<int32_t>(std::floor(pos_));
        if (base + half_ >= workFrames) break;
        const double frac = pos_ - static_cast<double>(base);
        int32_t phase = static_cast<int32_t>(frac * kPhases);
        if (phase < 0) phase = 0;
        if (phase >= kPhases) phase = kPhases - 1;
        const float* k = kernel_.data() + static_cast<size_t>(phase) * taps_;
        const float* srcFrame = work_.data() + static_cast<size_t>(base - half_ + 1) * channels_;
        for (int32_t c = 0; c < channels_; ++c) {
            float acc = 0.0f;
            const float* s = srcFrame + c;
            for (int32_t t = 0; t < taps_; ++t) {
                acc += s[static_cast<size_t>(t) * channels_] * k[t];
            }
            out[static_cast<size_t>(outFrames) * channels_ + c] = acc;
        }
        ++outFrames;
        pos_ += step_;
    }

    // Compactar conservando la ventana que necesita la próxima llamada.
    const int32_t processedUpTo = static_cast<int32_t>(std::floor(pos_));
    const int32_t keepFrom = std::max(0, processedUpTo - half_ + 1);
    if (keepFrom > 0 && keepFrom <= workFrames) {
        const int32_t keepFrames = workFrames - keepFrom;
        std::memmove(work_.data(),
                     work_.data() + static_cast<size_t>(keepFrom) * channels_,
                     sizeof(float) * static_cast<size_t>(keepFrames) * channels_);
        historyFrames_ = keepFrames;
        pos_ -= keepFrom;
    } else {
        historyFrames_ = workFrames;
    }
    return outFrames;
}

} // namespace audio
