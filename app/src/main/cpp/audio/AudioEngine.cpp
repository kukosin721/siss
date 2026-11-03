#include "audio/AudioEngine.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <cstdarg>
#include <thread>

#include <android/log.h>

namespace audio {

namespace {

constexpr const char* kLogTag = "KukosinAudio";

void logI(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
void logI(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_INFO, kLogTag, fmt, args);
    va_end(args);
}

void logW(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
void logW(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_WARN, kLogTag, fmt, args);
    va_end(args);
}

void logE(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
void logE(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_ERROR, kLogTag, fmt, args);
    va_end(args);
}

/** Tiempo mínimo que tarda un cambio completo de ganancia (suavizado, sin escalones). */
constexpr float kGainRampSeconds = 0.020f;

/** Intentos de rate alternativo antes de rendirse. */
constexpr int32_t MAX_RATE_ATTEMPTS = 3;

inline float clamp1(float x) {
    return x < -1.0f ? -1.0f : (x > 1.0f ? 1.0f : x);
}

inline int64_t nowNanos() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
                   std::chrono::steady_clock::now().time_since_epoch())
            .count();
}

oboe::AudioFormat toOboeFormat(PcmFormat format) {
    switch (format) {
        case PcmFormat::kI16: return oboe::AudioFormat::I16;
        case PcmFormat::kI24: return oboe::AudioFormat::I24;
        case PcmFormat::kI32: return oboe::AudioFormat::I32;
        case PcmFormat::kF32: return oboe::AudioFormat::Float;
    }
    return oboe::AudioFormat::I16;
}

PcmFormat fromOboeFormat(oboe::AudioFormat format) {
    switch (format) {
        case oboe::AudioFormat::I16: return PcmFormat::kI16;
        case oboe::AudioFormat::I24: return PcmFormat::kI24;
        case oboe::AudioFormat::I32: return PcmFormat::kI32;
        case oboe::AudioFormat::Float: return PcmFormat::kF32;
        default: return PcmFormat::kI16;
    }
}

/**
 * Decodifica PCM entrelazado a float normalizado (-1..1).
 *
 * Estas dos funciones sólo se usan en el camino con CONVERSIÓN (formato o canales
 * distintos del stream). El camino normal copia bytes y nunca pasa por acá: ésa es la
 * diferencia entre sonar fiel y sonar "saturado" por re-cuantizar cada bloque.
 */
void decodeSamples(const uint8_t* src, int32_t count, PcmFormat format, float* dst) {
    switch (format) {
        case PcmFormat::kI16: {
            const auto* s = reinterpret_cast<const int16_t*>(src);
            for (int32_t i = 0; i < count; ++i) dst[i] = s[i] * (1.0f / 32768.0f);
            break;
        }
        case PcmFormat::kI24: {
            for (int32_t i = 0; i < count; ++i) {
                const uint8_t* p = src + static_cast<size_t>(i) * 3;
                const int32_t v = static_cast<int32_t>(p[0]) |
                                  (static_cast<int32_t>(p[1]) << 8) |
                                  (static_cast<int32_t>(static_cast<int8_t>(p[2])) << 16);
                dst[i] = static_cast<float>(v) * (1.0f / 8388608.0f);
            }
            break;
        }
        case PcmFormat::kI32: {
            const auto* s = reinterpret_cast<const int32_t*>(src);
            for (int32_t i = 0; i < count; ++i) {
                dst[i] = static_cast<float>(static_cast<double>(s[i]) / 2147483648.0);
            }
            break;
        }
        case PcmFormat::kF32: {
            const auto* s = reinterpret_cast<const float*>(src);
            for (int32_t i = 0; i < count; ++i) dst[i] = s[i];
            break;
        }
    }
}

/** Codifica float (-1..1) a PCM entrelazado, redondeando y saturando. */
void encodeSamples(const float* src, int32_t count, PcmFormat format, uint8_t* dst) {
    switch (format) {
        case PcmFormat::kI16: {
            auto* d = reinterpret_cast<int16_t*>(dst);
            for (int32_t i = 0; i < count; ++i) {
                const double v = std::lrint(static_cast<double>(clamp1(src[i])) * 32768.0);
                d[i] = static_cast<int16_t>(v > 32767.0 ? 32767.0 : (v < -32768.0 ? -32768.0 : v));
            }
            break;
        }
        case PcmFormat::kI24: {
            for (int32_t i = 0; i < count; ++i) {
                double v = std::lrint(static_cast<double>(clamp1(src[i])) * 8388608.0);
                if (v > 8388607.0) v = 8388607.0;
                if (v < -8388608.0) v = -8388608.0;
                const int32_t iv = static_cast<int32_t>(v);
                uint8_t* p = dst + static_cast<size_t>(i) * 3;
                p[0] = static_cast<uint8_t>(iv & 0xFF);
                p[1] = static_cast<uint8_t>((iv >> 8) & 0xFF);
                p[2] = static_cast<uint8_t>((iv >> 16) & 0xFF);
            }
            break;
        }
        case PcmFormat::kI32: {
            auto* d = reinterpret_cast<int32_t*>(dst);
            for (int32_t i = 0; i < count; ++i) {
                double v = std::lrint(static_cast<double>(clamp1(src[i])) * 2147483648.0);
                if (v > 2147483647.0) v = 2147483647.0;
                if (v < -2147483648.0) v = -2147483648.0;
                d[i] = static_cast<int32_t>(v);
            }
            break;
        }
        case PcmFormat::kF32: {
            auto* d = reinterpret_cast<float*>(dst);
            for (int32_t i = 0; i < count; ++i) d[i] = clamp1(src[i]);
            break;
        }
    }
}

} // namespace

int32_t bytesPerSample(PcmFormat format) {
    switch (format) {
        case PcmFormat::kI16: return 2;
        case PcmFormat::kI24: return 3;
        case PcmFormat::kI32: return 4;
        case PcmFormat::kF32: return 4;
    }
    return 4;
}

const char* formatName(PcmFormat format) {
    switch (format) {
        case PcmFormat::kI16: return "16-bit";
        case PcmFormat::kI24: return "24-bit";
        case PcmFormat::kI32: return "32-bit";
        case PcmFormat::kF32: return "float32";
    }
    return "?";
}

// ---------------------------------------------------------------------------
// ByteRingBuffer
// ---------------------------------------------------------------------------

ByteRingBuffer::ByteRingBuffer() { resize(1u << 18); }

void ByteRingBuffer::resize(size_t capacity) {
    // Potencia de dos para usar máscara en vez de módulo.
    size_t cap = 1;
    while (cap < capacity) cap <<= 1;
    if (cap == capacity_) return;
    clear();
    buffer_.assign(cap, 0);
    capacity_ = cap;
    mask_ = cap - 1;
    readIndex_.store(0, std::memory_order_relaxed);
    writeIndex_.store(0, std::memory_order_relaxed);
}

size_t ByteRingBuffer::write(const uint8_t* data, size_t count) {
    if (count == 0 || capacity_ == 0) return 0;
    const size_t readIndex = readIndex_.load(std::memory_order_acquire);
    const size_t writeIndex = writeIndex_.load(std::memory_order_relaxed);
    const size_t space = capacity_ - (writeIndex - readIndex);
    const size_t n = std::min(count, space);
    if (n == 0) return 0;
    const size_t w = writeIndex & mask_;
    const size_t first = std::min(n, capacity_ - w);
    std::memcpy(buffer_.data() + w, data, first);
    if (n > first) {
        std::memcpy(buffer_.data(), data + first, n - first);
    }
    writeIndex_.store(writeIndex + n, std::memory_order_release);
    return n;
}

size_t ByteRingBuffer::read(uint8_t* out, size_t count) {
    if (count == 0 || capacity_ == 0) return 0;
    const size_t readIndex = readIndex_.load(std::memory_order_relaxed);
    const size_t writeIndex = writeIndex_.load(std::memory_order_acquire);
    const size_t available = writeIndex - readIndex;
    const size_t n = std::min(count, available);
    if (n == 0) return 0;
    const size_t r = readIndex & mask_;
    const size_t first = std::min(n, capacity_ - r);
    std::memcpy(out, buffer_.data() + r, first);
    if (n > first) {
        std::memcpy(out + first, buffer_.data(), n - first);
    }
    readIndex_.store(readIndex + n, std::memory_order_release);
    return n;
}

void ByteRingBuffer::clear() {
    readIndex_.store(writeIndex_.load(std::memory_order_acquire), std::memory_order_release);
}

size_t ByteRingBuffer::availableToRead() const {
    const size_t w = writeIndex_.load(std::memory_order_acquire);
    const size_t r = readIndex_.load(std::memory_order_acquire);
    return w - r;
}

size_t ByteRingBuffer::freeSpace() const {
    return capacity_ - availableToRead();
}

// ---------------------------------------------------------------------------
// AudioEngine
// ---------------------------------------------------------------------------

AudioEngine::AudioEngine() = default;

AudioEngine::~AudioEngine() { close(); }

oboe::Result AudioEngine::openStream(PcmFormat streamFormat,
                                     int32_t streamRate,
                                     int32_t nativeRate,
                                     int32_t streamChannels,
                                     oboe::SharingMode sharing,
                                     oboe::PerformanceMode performance) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    // Unspecified: Oboe elige AAudio en API 26+ y OpenSL ES en equipos antiguos.
    builder.setAudioApi(oboe::AudioApi::Unspecified);
    builder.setFormat(toOboeFormat(streamFormat));
    builder.setSampleRate(nativeRate > 0 ? nativeRate : 48000);
    builder.setChannelCount(streamChannels > 0 ? streamChannels : 2);
    builder.setSharingMode(sharing);
    builder.setPerformanceMode(performance);
    builder.setUsage(oboe::Usage::Media);
    builder.setContentType(oboe::ContentType::Music);
    builder.setSpatializationBehavior(oboe::SpatializationBehavior::Never);
    builder.setIsContentSpatialized(false);
    // Nada de conversiones silenciosas: si el formato no es nativo, el motor prueba
    // otro candidato él mismo y lo informa. Un stream que "dice" 44.1 kHz y suena a
    // 48 lo convirtió el HAL, y eso hay que saberlo para no mentir en la UI.
    builder.setFormatConversionAllowed(false);
    builder.setChannelConversionAllowed(false);
    builder.setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None);
    builder.setDataCallback(this);
    builder.setErrorCallback(this);
    if (deviceId_ > 0) {
        builder.setDeviceId(deviceId_);
    }
    std::shared_ptr<oboe::AudioStream> stream;
    const oboe::Result result = builder.openStream(stream);
    if (result != oboe::Result::OK) {
        return result;
    }

    // Exclusivo y formato se PIDEN, no se garantizan. Si el modo lo exige y el HAL no
    // lo dio, se descarta el stream en vez de mostrar "exclusivo" cuando no lo es.
    if (sharing == oboe::SharingMode::Exclusive &&
        stream->getSharingMode() != oboe::SharingMode::Exclusive) {
        logW("el HAL no concedió exclusivo (devolvió %d): se descarta este stream",
             static_cast<int>(stream->getSharingMode()));
        stream->close();
        return oboe::Result::ErrorUnavailable;
    }
    if (stream->getFormat() != toOboeFormat(streamFormat) ||
        stream->getChannelCount() != (streamChannels > 0 ? streamChannels : 2)) {
        logW("el HAL cambió el formato pedido (%s %d ch -> %s %d ch)",
             formatName(streamFormat), streamChannels,
             formatName(fromOboeFormat(stream->getFormat())), stream->getChannelCount());
        halConverted_ = true;
    }
    if (nativeRate > 0 && stream->getSampleRate() != nativeRate) {
        logW("el HAL cambió el rate pedido (%d -> %d)", nativeRate, stream->getSampleRate());
        halConverted_ = true;
    }
    stream_ = stream;
    streamRate_ = streamRate > 0 ? streamRate : 48000;
    nativeRate_ = stream->getSampleRate() > 0 ? stream->getSampleRate() : streamRate_;
    return oboe::Result::OK;
}

oboe::Result AudioEngine::tryOpenCandidates(const EngineConfig& config) {
    const oboe::SharingMode strictSharing = oboe::SharingMode::Exclusive;
    const oboe::SharingMode looseSharing = config.requireExclusive ? oboe::SharingMode::Exclusive
                                                                 : oboe::SharingMode::Shared;
    oboe::Result last = oboe::Result::ErrorUnavailable;

    // 1) Exacto + exclusivo + low latency (camino MMAP si el equipo lo permite).
    last = openStream(sourceFormat_, sourceRate_, sourceRate_, sourceChannels_,
                      strictSharing, oboe::PerformanceMode::LowLatency);
    if (last == oboe::Result::OK) return last;
    logW("exclusivo %s @ %d falló: %s", formatName(sourceFormat_), sourceRate_,
         oboe::convertToText(last));

    // 2) Exacto, con la política de sharing que permite el modo.
    last = openStream(sourceFormat_, sourceRate_, sourceRate_, sourceChannels_,
                      looseSharing, oboe::PerformanceMode::LowLatency);
    if (last == oboe::Result::OK) return last;
    logW("compartido %s @ %d falló: %s", formatName(sourceFormat_), sourceRate_,
         oboe::convertToText(last));

    // 3) Exacto sin exigir low latency (HAL viejos que rechazan MMAP).
    last = openStream(sourceFormat_, sourceRate_, sourceRate_, sourceChannels_,
                      looseSharing, oboe::PerformanceMode::None);
    if (last == oboe::Result::OK) return last;
    logW("sin low-latency %s @ %d falló: %s", formatName(sourceFormat_), sourceRate_,
         oboe::convertToText(last));

    // 4) Rate nativo del equipo con el formato de la pista: el motor convierte el rate
    //    una sola vez por stream y lo informa (!passthrough). Es lo que hace que en un
    //    equipo con HAL fijo a 48 kHz una pista de 44.1 no se caiga a 16 bits.
    if (!config.strictBitPerfect) {
        const int32_t rates[] = {48000, 44100, 96000, 192000};
        int attempts = 0;
        for (int32_t rate : rates) {
            if (rate == sourceRate_) continue;
            if (attempts >= MAX_RATE_ATTEMPTS) break;
            ++attempts;
            last = openStream(sourceFormat_, rate, rate, sourceChannels_,
                              looseSharing, oboe::PerformanceMode::LowLatency);
            if (last == oboe::Result::OK) {
                logI("rate de fallback aceptado: %s @ %d (fuente %d Hz, conversión propia)",
                     formatName(sourceFormat_), rate, sourceRate_);
                return last;
            }
        }

        // 5) Formato nativo del equipo, al rate de la pista (conversión de formato).
        const PcmFormat formats[] = {PcmFormat::kI32, PcmFormat::kI24, PcmFormat::kI16,
                                     PcmFormat::kF32};
        for (PcmFormat format : formats) {
            if (format == sourceFormat_) continue;
            last = openStream(format, sourceRate_, sourceRate_, sourceChannels_,
                              looseSharing, oboe::PerformanceMode::LowLatency);
            if (last == oboe::Result::OK) {
                logI("formato de fallback aceptado: %s @ %d (fuente %s)",
                     formatName(format), sourceRate_, formatName(sourceFormat_));
                return last;
            }
        }
    }
    return last;
}

oboe::Result AudioEngine::open(const EngineConfig& config) {
    // El hilo anterior debe haber terminado ANTES de tomar el mutex (si no, dos
    // candados cruzados: deadlock).
    joinOpenThread();

    {
        std::lock_guard<std::mutex> guard(controlMutex_);
        closeLocked();

        sourceFormat_ = config.sourceFormat;
        sourceChannels_ = config.sourceChannels > 0 ? config.sourceChannels : 2;
        sourceRate_ = config.sourceSampleRate > 0 ? config.sourceSampleRate : 48000;
        deviceId_ = config.deviceId;
        errorCode_.store(0, std::memory_order_relaxed);
        underruns_.store(0, std::memory_order_relaxed);
        halConverted_ = false;
        resetStreamAccounting();
        pendingConfig_ = config;
        openOutcome_.store(0, std::memory_order_relaxed);
        openPending_.store(true, std::memory_order_release);
    }

    logI("open (async): source=%s %d Hz %d ch strict=%d exclusiveOnly=%d requireDevice=%d "
         "buffer=%dms device=%d",
         formatName(config.sourceFormat),
         config.sourceSampleRate > 0 ? config.sourceSampleRate : 48000,
         config.sourceChannels > 0 ? config.sourceChannels : 2,
         config.strictBitPerfect ? 1 : 0, config.requireExclusive ? 1 : 0,
         config.requireDevice ? 1 : 0, config.bufferMs, config.deviceId);

    openThread_ = std::thread([this] { openThreadMain(); });

    // Se devuelve OK = "la configuración quedó aceptada". El resultado real está en
    // awaitOpen(), que es donde Media3 puede reaccionar con WriteException.
    return oboe::Result::OK;
}

void AudioEngine::openThreadMain() {
    oboe::Result result = oboe::Result::ErrorNull;

    const EngineConfig config = pendingConfig_;
    {
        std::lock_guard<std::mutex> guard(controlMutex_);
        if (!openPending_.load(std::memory_order_acquire)) {
            openOutcome_.store(static_cast<int32_t>(oboe::Result::ErrorNull),
                               std::memory_order_release);
            return;
        }
        deviceId_ = config.deviceId;
        result = tryOpenCandidates(config);
        if (result != oboe::Result::OK && deviceId_ > 0 && !config.requireDevice) {
            logW("el dispositivo %d no se pudo abrir; se reintenta con el predeterminado",
                 deviceId_);
            deviceId_ = 0;
            result = tryOpenCandidates(config);
        }
        if (result == oboe::Result::OK) {
            finalizeOpen(config);
        } else {
            logE("no se pudo abrir ningún stream: %s", oboe::convertToText(result));
            errorCode_.store(static_cast<int32_t>(result), std::memory_order_relaxed);
        }
    }
    openOutcome_.store(static_cast<int32_t>(result), std::memory_order_release);
    openPending_.store(false, std::memory_order_release);
}

void AudioEngine::joinOpenThread() {
    if (openThread_.joinable()) {
        openThread_.join();
    }
}

bool AudioEngine::awaitOpen(int32_t timeoutMs) {
    const int64_t deadline = nowNanos() + static_cast<int64_t>(timeoutMs) * 1000000LL;
    while (openPending_.load(std::memory_order_acquire)) {
        if (nowNanos() >= deadline) return false;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    const int32_t outcome = openOutcome_.load(std::memory_order_acquire);
    if (outcome != 0) {
        errorCode_.store(outcome, std::memory_order_relaxed);
        return false;
    }
    return stream_ != nullptr;
}

void AudioEngine::finalizeOpen(const EngineConfig& config) {
    streamFormat_ = static_cast<int32_t>(fromOboeFormat(stream_->getFormat()));
    if (nativeRate_ <= 0) nativeRate_ = stream_->getSampleRate();
    if (streamRate_ <= 0) streamRate_ = nativeRate_ > 0 ? nativeRate_ : 48000;
    streamChannels_ = stream_->getChannelCount();
    streamFrameBytes_ = streamChannels_ * bytesPerSample(static_cast<PcmFormat>(streamFormat_));
    if (streamFrameBytes_ <= 0) streamFrameBytes_ = 4;
    apiCode_ = stream_->getAudioApi() == oboe::AudioApi::AAudio ? 0 : 1;
    exclusive_ = stream_->getSharingMode() == oboe::SharingMode::Exclusive;
    mmap_ = false;
#ifdef AUDIOENGINE_HAVE_OBOE_EXTENSIONS
    mmap_ = oboe::OboeExtensions::isMMapUsed(stream_.get());
#endif
    deviceId_ = stream_->getDeviceId() > 0 ? stream_->getDeviceId() : deviceId_;

    // Passthrough real: el stream quedó en el formato Y rate de la pista. Si el HAL
    // impuso otro rate/formato, no hay bit-perfect y se informa.
    passthrough_ = static_cast<PcmFormat>(streamFormat_) == sourceFormat_ &&
                   nativeRate_ == sourceRate_ &&
                   streamChannels_ == sourceChannels_ &&
                   !halConverted_;

    // El resampler se configura UNA vez por stream (nunca por bloque).
    resampling_ = !passthrough_ && nativeRate_ != sourceRate_ &&
                  static_cast<PcmFormat>(streamFormat_) == sourceFormat_;
    if (resampling_) {
        resampler_.configure(sourceRate_, nativeRate_, streamChannels_);
    } else {
        resampler_.configure(nativeRate_, nativeRate_, streamChannels_);
    }

    // Rampas. La ganancia arranca en el objetivo del volumen actual, NO en 0: arrancar
    // en 0 y "recuperar" el nivel es lo que se percibía como salto de volumen.
    fades_.store(config.fades, std::memory_order_relaxed);
    const int32_t rate = streamRate_ > 0 ? streamRate_ : 48000;
    const int32_t fadeFrames = std::max(32, static_cast<int32_t>(rate * kGainRampSeconds));
    fadeInFrames_.store(config.fades ? fadeFrames : 0, std::memory_order_relaxed);
    fadeOutFrames_.store(config.fades ? fadeFrames : 0, std::memory_order_relaxed);
    rearmFrames_.store(config.fades ? fadeFrames : 0, std::memory_order_relaxed);

    gain_ = config.fades ? 0.0f : targetGain();
    gainStep_ = 0.0f;
    stopAfterFade_ = false;
    holdingLogged_ = false;
    fadeRequest_.store(kFadeNone, std::memory_order_relaxed);
    holding_.store(false, std::memory_order_relaxed);
    endOfStream_.store(false, std::memory_order_relaxed);

    // Reparto de buffers:
    //  - ABAJO (HAL): el buffer más chico razonable; es lo que se descarta en un salto.
    //  - ARRIBA (anillo propio): el buffer del ajuste, donde vive la estabilidad.
    const int32_t burst = stream_->getFramesPerBurst() > 0 ? stream_->getFramesPerBurst() : 192;
    const int32_t capacity = stream_->getBufferCapacityInFrames();
    int32_t requestedFrames = exclusive_ ? burst * 2 : burst * 3;
    if (mmap_) requestedFrames = burst;
    requestedFrames = std::max(requestedFrames, burst);
    if (capacity > 0) requestedFrames = std::min(capacity, requestedFrames);
    int32_t actualFrames = requestedFrames;
    if (requestedFrames > 0) {
        stream_->setBufferSizeInFrames(requestedFrames);
        const int32_t actual = stream_->getBufferSizeInFrames();
        if (actual > 0) actualFrames = actual;
    }
    hardwareBufferFrames_.store(actualFrames, std::memory_order_relaxed);
    // El contador de frames consumidos arranca exactamente al abrir el stream: así el
    // timestamp del HAL y `framesConsumed` comparten origen y su diferencia ES latencia
    // (no ruido). Sin esto, "posición" y "latencia" se movían a saltos.
    framesConsumed_.store(0, std::memory_order_relaxed);
    lastPositionFrames_.store(0, std::memory_order_relaxed);
    timestampAnchorNanos_.store(0, std::memory_order_relaxed);
    timestampAnchorFrames_.store(0, std::memory_order_relaxed);
    halPositionBase_.store(-1, std::memory_order_relaxed);

    // Preroll: media ráfaga alcanza para que el callback tenga con qué arrancar. Con
    // `prerollMs` configurado se respeta (los presets "sin cortes" usan 0).
    const int32_t configuredPrerollMs = config.prerollMs;
    int32_t preroll = configuredPrerollMs >= 0
            ? std::max(burst, streamRate_ * configuredPrerollMs / 1000)
            : std::max(burst / 2, 1);
    preroll = std::min(preroll, burst * 8);
    prerollFrames_.store(preroll, std::memory_order_relaxed);
    startPending_.store(false, std::memory_order_relaxed);

    // Anillo: `bufferMs` de audio (mínimo 200 ms), entre 256 KB y 8 MB.
    const size_t bytesPerSecond = static_cast<size_t>(streamRate_) *
                                  static_cast<size_t>(streamFrameBytes_);
    const int32_t bufferMs = std::max(config.bufferMs, 200);
    size_t ringBytes = bytesPerSecond * static_cast<size_t>(bufferMs) / 1000;
    ringBytes = std::max<size_t>(ringBytes, 1u << 18);
    ringBytes = std::min<size_t>(ringBytes, 1u << 23);
    ring_.resize(ringBytes);
    staged_.clear();

    logI("opened: api=%d stream=%s@%d ch=%d nativeRate=%d burst=%d hwBuffer=%d ring=%zuKB "
         "preroll=%d exclusive=%d mmap=%d passthrough=%d resampling=%d halConverted=%d",
         apiCode_, formatName(static_cast<PcmFormat>(streamFormat_)), streamRate_,
         streamChannels_, nativeRate_, burst, actualFrames, ringBytes / 1024, preroll,
         exclusive_ ? 1 : 0, mmap_ ? 1 : 0, passthrough_ ? 1 : 0, resampling_ ? 1 : 0,
         halConverted_ ? 1 : 0);
}

oboe::Result AudioEngine::close() {
    joinOpenThread();
    std::lock_guard<std::mutex> guard(controlMutex_);
    closeLocked();
    return oboe::Result::OK;
}

void AudioEngine::closeLocked() {
    openPending_.store(false, std::memory_order_release);
    holding_.store(true, std::memory_order_relaxed);
    startPending_.store(false, std::memory_order_relaxed);
    if (stream_) {
        stream_->requestStop();
        stream_->close();
        stream_.reset();
    }
}

void AudioEngine::resetStreamAccounting() {
    timestampAnchorNanos_.store(0, std::memory_order_relaxed);
    timestampAnchorFrames_.store(0, std::memory_order_relaxed);
    framesConsumed_.store(0, std::memory_order_relaxed);
    framesQueued_.store(0, std::memory_order_relaxed);
    framesDropped_.store(0, std::memory_order_relaxed);
    lastLatencyFrames_.store(0, std::memory_order_relaxed);
    lastPositionFrames_.store(0, std::memory_order_relaxed);
}

oboe::Result AudioEngine::setStreamState(int32_t target) {
    if (!stream_) return oboe::Result::ErrorInvalidState;
    oboe::Result result;
    switch (target) {
        case 0: result = stream_->requestStart(); break;
        case 1: result = stream_->requestPause(); break;
        case 2: result = stream_->requestStop(); break;
        case 3: result = stream_->requestFlush(); break;
        default: return oboe::Result::ErrorInvalidState;
    }
    if (result != oboe::Result::OK) {
        logW("transición %d falló: %s", target, oboe::convertToText(result));
    }
    return result;
}

bool AudioEngine::waitForState(std::initializer_list<oboe::StreamState> states,
                               int32_t timeoutMs) {
    if (!stream_) return false;
    const int64_t deadline = nowNanos() + static_cast<int64_t>(timeoutMs) * 1000000LL;
    oboe::StreamState current = stream_->getState();
    for (;;) {
        for (oboe::StreamState wanted : states) {
            if (current == wanted) return true;
        }
        if (current == oboe::StreamState::Closed || current == oboe::StreamState::Disconnected) {
            return false;
        }
        const int64_t remainingNs = deadline - nowNanos();
        if (remainingNs <= 0) return false;
        const int64_t sliceNs = std::min<int64_t>(remainingNs, 5 * 1000000LL);
        oboe::StreamState nextState = current;
        if (stream_->waitForStateChange(current, &nextState, sliceNs) != oboe::Result::OK) {
            if (stream_->getState() == current && deadline - nowNanos() <= 0) return false;
        }
        current = nextState;
    }
}

oboe::Result AudioEngine::start() {
    if (openPending_.load(std::memory_order_acquire) && !awaitOpen()) {
        return static_cast<oboe::Result>(errorCode_.load(std::memory_order_relaxed));
    }
    std::lock_guard<std::mutex> guard(controlMutex_);
    holding_.store(false, std::memory_order_release);
    endOfStream_.store(false, std::memory_order_release);
    stopAfterFade_ = false;
    // La subida la aplica el callback partiendo de la ganancia REAL (no de 0).
    fadeRequest_.store(fades_.load(std::memory_order_relaxed) ? kFadeIn : kFadeNone,
                       std::memory_order_release);
    if (!stream_) return oboe::Result::ErrorInvalidState;
    startPending_.store(true, std::memory_order_release);
    return maybeStart();
}

oboe::Result AudioEngine::maybeStart() {
    if (!stream_ || !startPending_.load(std::memory_order_acquire)) return oboe::Result::OK;
    if (stream_->getState() == oboe::StreamState::Started) {
        startPending_.store(false, std::memory_order_release);
        return oboe::Result::OK;
    }
    const size_t needBytes =
            static_cast<size_t>(prerollFrames_.load(std::memory_order_relaxed)) *
            static_cast<size_t>(streamFrameBytes_);
    if (needBytes > 0 && ring_.availableToRead() < needBytes) {
        return oboe::Result::OK;   // todavía no hay datos: se reintenta desde write()
    }
    startPending_.store(false, std::memory_order_release);
    // El HAL numera desde que el stream arranca: éste es el momento de fijar el origen.
    halPositionBase_.store(-1, std::memory_order_relaxed);
    lastPositionFrames_.store(0, std::memory_order_relaxed);
    return setStreamState(0);
}

oboe::Result AudioEngine::pause() {
    std::lock_guard<std::mutex> guard(controlMutex_);
    startPending_.store(false, std::memory_order_release);
    if (!stream_) return oboe::Result::ErrorInvalidState;
    if (stream_->getState() != oboe::StreamState::Started) {
        fadeRequest_.store(kFadeNone, std::memory_order_release);
        return oboe::Result::OK;
    }
    if (!fades_.load(std::memory_order_relaxed)) {
        fadeRequest_.store(kFadeNone, std::memory_order_release);
        return setStreamState(1);
    }
    fadeRequest_.store(kFadeOutAndHold, std::memory_order_release);
    return oboe::Result::OK;
}

oboe::Result AudioEngine::stop() {
    std::lock_guard<std::mutex> guard(controlMutex_);
    startPending_.store(false, std::memory_order_release);
    if (!stream_) return oboe::Result::ErrorInvalidState;

    // `AudioOutput.stop()` de Media3 = "deja sonar lo que queda y para". No se vacía el
    // anillo ni se pide requestStop(): el callback sigue drenando y se detiene él mismo
    // cuando el DAC consumió todo. Pedir requestStop acá tira lo que no sonó.
    endOfStream_.store(true, std::memory_order_release);
    if (stream_->getState() != oboe::StreamState::Started) {
        holding_.store(true, std::memory_order_relaxed);
        return oboe::Result::OK;
    }
    if (ring_.availableToRead() == 0) {
        holding_.store(true, std::memory_order_relaxed);
        return oboe::Result::OK;
    }
    if (fades_.load(std::memory_order_relaxed)) {
        fadeRequest_.store(kFadeOutAndHold, std::memory_order_release);
    }
    return oboe::Result::OK;
}

oboe::Result AudioEngine::flush() { return flushInternal(/*waitForHardware=*/true); }

oboe::Result AudioEngine::flushForTransition() {
    return flushInternal(/*waitForHardware=*/false);
}

oboe::Result AudioEngine::flushInternal(bool waitForHardware) {
    std::lock_guard<std::mutex> guard(controlMutex_);
    startPending_.store(false, std::memory_order_release);
    endOfStream_.store(false, std::memory_order_release);

    // 1) Los datos propios se descartan siempre: es memoria nuestra e instantáneo.
    const size_t dropped = ring_.availableToRead();
    ring_.clear();
    if (resampling_) resampler_.reset();
    if (streamFrameBytes_ > 0) {
        framesDropped_.fetch_add(static_cast<int64_t>(dropped / streamFrameBytes_),
                                 std::memory_order_relaxed);
    }
    resetStreamAccounting();

    if (!stream_) return oboe::Result::OK;

    const oboe::StreamState current = stream_->getState();
    if (current == oboe::StreamState::Closed || current == oboe::StreamState::Disconnected) {
        return oboe::Result::OK;
    }

    // 2) AAudio sólo acepta requestFlush() en OPEN/PAUSED/STOPPED/FLUSHED: se pausa
    //    primero. Con `waitForHardware = false` no se espera al HAL.
    if (current == oboe::StreamState::Started ||
        current == oboe::StreamState::Starting ||
        current == oboe::StreamState::Pausing ||
        current == oboe::StreamState::Flushing) {
        if (setStreamState(1) != oboe::Result::OK) setStreamState(2);
        if (waitForHardware &&
            !waitForState({oboe::StreamState::Paused, oboe::StreamState::Stopped}, 120)) {
            logW("flush: el stream no llegó a PAUSED; se descarta la operación");
        }
    }

    const oboe::StreamState ready = stream_->getState();
    oboe::Result result = oboe::Result::OK;
    if (ready == oboe::StreamState::Paused || ready == oboe::StreamState::Stopped ||
        ready == oboe::StreamState::Open) {
        result = setStreamState(3);
        if (result == oboe::Result::OK && waitForHardware) {
            waitForState({oboe::StreamState::Flushed, oboe::StreamState::Paused}, 120);
        }
    } else if (ready != oboe::StreamState::Flushed && waitForHardware) {
        result = oboe::Result::ErrorInvalidState;
        logW("flush: estado inesperado %d", static_cast<int32_t>(ready));
    }

    // 3) Queda en PAUSED: es el estado desde el que `start()` reanuda sin reabrir.
    const oboe::StreamState afterFlush = stream_->getState();
    if (afterFlush == oboe::StreamState::Flushed ||
        afterFlush == oboe::StreamState::Started) {
        setStreamState(1);
        if (waitForHardware) waitForState({oboe::StreamState::Paused}, 60);
    }

    // 4) Estado de señal limpio: el próximo arranque entra con rampa corta.
    gain_ = fades_.load(std::memory_order_relaxed) ? 0.0f : targetGain();
    gainStep_ = 0.0f;
    stopAfterFade_ = false;
    holdingLogged_ = false;
    fadeRequest_.store(fades_.load(std::memory_order_relaxed) ? kFadeRearm : kFadeNone,
                       std::memory_order_release);
    return result;
}

bool AudioEngine::drain(int32_t timeoutMs) {
    const int64_t deadline = nowNanos() + static_cast<int64_t>(timeoutMs) * 1000000LL;
    for (;;) {
        if (stream_ == nullptr) return true;
        const bool ringEmpty = ring_.availableToRead() == 0;
        const bool stateStopped = stream_->getState() != oboe::StreamState::Started;
        if (ringEmpty && (stateStopped || positionFrames() >=
                                                framesQueued_.load(std::memory_order_relaxed))) {
            return true;
        }
        if (nowNanos() >= deadline) return false;
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
}

void AudioEngine::setVolume(float volume) {
    volume_.store(std::min(std::max(volume, 0.0f), 1.0f), std::memory_order_relaxed);
}

void AudioEngine::setVolumeCurve(int32_t curve) {
    volumeCurve_.store(curve == 1 ? 1 : 0, std::memory_order_relaxed);
}

float AudioEngine::targetGain() const {
    const float volume = volume_.load(std::memory_order_relaxed);
    if (volumeCurve_.load(std::memory_order_relaxed) == 0) return volume;
    // Curva perceptual: v^3. A 1.0 da exactamente 1.0 (bit-perfect intacto) y reparte
    // el recorrido del deslizador como lo siente el oído.
    return volume * volume * volume;
}

void AudioEngine::setFadesEnabled(bool enabled) {
    fades_.store(enabled, std::memory_order_relaxed);
    if (!enabled) {
        gain_ = targetGain();
        gainStep_ = 0.0f;
        stopAfterFade_ = false;
    }
}

// ---------------------------------------------------------------------------
// Reloj de playout
// ---------------------------------------------------------------------------

void AudioEngine::updatePlayoutPosition(bool forceZeroLatency) {
    const int64_t consumed = framesConsumed_.load(std::memory_order_relaxed);
    if (!stream_) {
        lastPositionFrames_.store(consumed, std::memory_order_relaxed);
        return;
    }
    const int64_t hwBuffer =
            std::max<int64_t>(1, hardwareBufferFrames_.load(std::memory_order_relaxed));
    int64_t latencyFrames = lastLatencyFrames_.load(std::memory_order_relaxed);
    const bool playing = stream_->getState() == oboe::StreamState::Started;

    if (!forceZeroLatency && playing) {
        int64_t hardwarePosition = 0;
        int64_t hardwareTime = 0;
        if (stream_->getTimestamp(CLOCK_MONOTONIC, &hardwarePosition, &hardwareTime) ==
            oboe::Result::OK) {
            // La posición del HAL son los frames presentados en el DAC; la del motor,
            // los frames entregados al HAL. La diferencia es lo que aún está en el
            // buffer del hardware. Se acota para que un timestamp raro no mueva el reloj.
            // La posición del HAL manda: es la única que dice qué sonó de verdad.
            // NO se resta `consumed` (frames copiados por el callback): los dos contadores
            // no arrancan en el mismo punto y la diferencia no es latencia, es ruido de
            // decenas de miles de frames. Restar `consumed` era lo que hacía que la barra
            // de posición saltara hacia adelante.
            // La posición del HAL es ABSOLUTA desde que abrió el stream. Como el stream
            // no se reabre dentro de una sesión, su origen es fijo: se guarda la primera
            // lectura y se informa la DIFERENCIA. Así, aunque el HAL arranque su cuenta
            // en otro punto (o la reinicie), lo que se escucha y lo que se informa siguen
            // coincidiendo y la barra no salta.
            const int64_t base = halPositionBase_.load(std::memory_order_relaxed);
            const int64_t relative = hardwarePosition - (base > 0 ? base : 0);
            // `-1` = todavía no se leyó nunca; `0` = el HAL no presentó nada todavía.
            if (base == -1 && hardwarePosition > 0) {
                halPositionBase_.store(hardwarePosition, std::memory_order_relaxed);
            }
            int64_t presented = relative;
            if (presented < 0) presented = 0;
            // El ancla guarda SIEMPRE lo que dijo el HAL (sin piso): si el HAL reinicia
            // su contador —stream reabierto, cambio de origen—, el ancla tiene que
            // seguirlo, o la posición se queda congelada para siempre.
            timestampAnchorFrames_.store(presented, std::memory_order_relaxed);
            timestampAnchorNanos_.store(hardwareTime, std::memory_order_relaxed);
            // Lo que se INFORMA sí es monótono: nunca retrocede...
            const int64_t floor = lastPositionFrames_.load(std::memory_order_relaxed);
            if (presented < floor) presented = floor;
            // ...y nunca va por delante de lo que el motor entregó al HAL.
            const int64_t ceiling = framesConsumed_.load(std::memory_order_relaxed);
            if (ceiling > 0 && presented > ceiling) presented = ceiling;
            // Con timestamp, `presented` YA es el playout: no hay latencia que restar.
            // Se guarda también, porque `latencyMicros()` lee este mismo campo y si no
            // devolvería la latencia de la llamada anterior.
            lastLatencyFrames_.store(0, std::memory_order_relaxed);
            lastPositionFrames_.store(presented, std::memory_order_relaxed);
            return;
        } else {
            // Entre refrescos del timestamp: se extrapola desde el ancla con el reloj
            // monótono (así la barra no se mueve a saltitos).
            const int64_t anchorNanos = timestampAnchorNanos_.load(std::memory_order_relaxed);
            const int64_t now = nowNanos();
            if (anchorNanos > 0 && now > anchorNanos && streamRate_ > 0) {
                const int64_t elapsedFrames =
                        (now - anchorNanos) * static_cast<int64_t>(streamRate_) / 1000000000LL;
                (void)consumed;
                (void)hwBuffer;
                const int64_t presented =
                        timestampAnchorFrames_.load(std::memory_order_relaxed) + elapsedFrames;
                // Sin timestamp fresco, la posición se estima con el reloj monótono desde
                // el último ancla real. Es una cota, no un dato del HAL: por eso no se usa
                // para latencia y nunca supera lo consumido.
                if (presented > consumed) return;
                lastPositionFrames_.store(
                        std::max(presented, lastPositionFrames_.load(std::memory_order_relaxed)),
                        std::memory_order_relaxed);
                latencyFrames = std::max<int64_t>(0, consumed - presented);
                if (latencyFrames > hwBuffer) latencyFrames = hwBuffer;
            }
        }
    } else if (!forceZeroLatency) {
        // Pausado: lo que el buffer del HAL todavía tenga no va a sonar hasta reanudar.
        latencyFrames = std::min(consumed, hwBuffer);
    }
    if (forceZeroLatency) latencyFrames = 0;
    lastLatencyFrames_.store(latencyFrames, std::memory_order_relaxed);

    int64_t position = consumed - latencyFrames;
    if (position < 0) position = 0;
    if (position > consumed) position = consumed;

    // Cota inferior monótona: la posición de playout no retrocede dentro del stream. Si
    // retrocediera, Media3 cree que todavía no sonó lo que ya sonó y vuelve a alimentar.
    const int64_t previous = lastPositionFrames_.load(std::memory_order_relaxed);
    if (position < previous) position = previous;
    lastPositionFrames_.store(position, std::memory_order_relaxed);
}

int64_t AudioEngine::positionFrames() {
    updatePlayoutPosition(false);
    return lastPositionFrames_.load(std::memory_order_relaxed);
}

int64_t AudioEngine::positionMicros() {
    const int64_t frames = positionFrames();
    if (streamRate_ <= 0) return 0;
    // Se devuelve en el dominio temporal del ORIGEN (lo que espera Media3).
    const int64_t rate = sourceRate_ > 0 ? sourceRate_ : streamRate_;
    const int64_t sourceFrames = frames * rate / streamRate_;
    return sourceFrames * 1000000LL / rate;
}

int64_t AudioEngine::latencyMicros() {
    if (streamRate_ <= 0 || !stream_) return -1;
    updatePlayoutPosition(false);
    const int64_t frames = lastLatencyFrames_.load(std::memory_order_relaxed);
    return frames * 1000000LL / streamRate_;
}

int64_t AudioEngine::sourceFramesQueued() const {
    const int64_t queued = framesQueued_.load(std::memory_order_relaxed);
    if (streamRate_ <= 0 || sourceRate_ <= 0 || streamRate_ == sourceRate_) return queued;
    return queued * sourceRate_ / streamRate_;
}

bool AudioEngine::endOfStreamReached() const {
    if (!endOfStream_.load(std::memory_order_acquire)) return false;
    return ring_.availableToRead() == 0;
}

oboe::StreamState AudioEngine::state() const {
    return stream_ ? stream_->getState() : oboe::StreamState::Closed;
}

StreamInfo AudioEngine::info() const {
    StreamInfo info;
    info.api = apiCode_;
    info.sourceFormat = static_cast<int32_t>(sourceFormat_);
    info.sourceSampleRate = sourceRate_;
    info.sourceChannels = sourceChannels_;
    // Volumen EFECTIVO de la señal: si un modo delega el volumen al hardware, la app
    // pone 1.0 y eso es lo que sale por el DAC.
    info.volumePercent = static_cast<int32_t>(
            volume_.load(std::memory_order_relaxed) * 100.0f + 0.5f);
    info.format = streamFormat_;
    info.sampleRate = streamRate_;
    info.channels = streamChannels_;
    info.deviceId = stream_ ? stream_->getDeviceId() : deviceId_;
    info.errorCode = errorCode_.load(std::memory_order_relaxed);
    info.latencyMicros = streamRate_ > 0
            ? static_cast<int32_t>(lastLatencyFrames_.load(std::memory_order_relaxed) *
                                   1000000LL / streamRate_)
            : -1;
    info.underruns = static_cast<int32_t>(underruns_.load(std::memory_order_relaxed));
    info.mmap = mmap_;
    info.halConverted = halConverted_;
    if (stream_) {
        info.framesPerBurst = stream_->getFramesPerBurst();
        info.bufferFrames = stream_->getBufferSizeInFrames();
        // El anillo es el colchón real: es lo que absorbe los huecos del HAL y lo que
        // Media3 tiene que tener en cuenta para alimentar (no el buffer del HAL, que con
        // el altavoz interno es de una ráfaga).
        if (streamFrameBytes_ > 0) {
            info.ringFrames = static_cast<int32_t>(ring_.capacity() / streamFrameBytes_);
        }
        info.exclusive = exclusive_;
        info.passthrough = passthrough_;
        info.bitPerfect = exclusive_ && passthrough_ && gain_ >= 1.0f &&
                          targetGain() >= 1.0f;
        const auto xRuns = stream_->getXRunCount();
        const int64_t nativeXRuns = xRuns.error() == oboe::Result::OK ? xRuns.value() : 0;
        info.xRuns = static_cast<int32_t>(nativeXRuns + underruns_.load(std::memory_order_relaxed));
    }
    return info;
}

// ---------------------------------------------------------------------------
// Escritura (hilo productor de Media3)
// ---------------------------------------------------------------------------

size_t AudioEngine::write(const uint8_t* data, size_t byteCount) {
    if (data == nullptr || byteCount == 0) return 0;

    if (openPending_.load(std::memory_order_acquire) && !awaitOpen()) {
        return 0;
    }
    // Si el stream murió (DAC desconectado) no se acepta nada: el sink debe fallar y
    // reconstruirse, no quedarse reintentando eternamente con un stream muerto.
    if (!stream_ || errorCode_.load(std::memory_order_relaxed) != 0) return 0;

    const size_t accepted = writeToRing(data, byteCount);
    if (startPending_.load(std::memory_order_acquire)) {
        maybeStart();
    }
    return accepted;
}

size_t AudioEngine::writeToRing(const uint8_t* data, size_t byteCount) {
    const size_t frameBytes = streamFrameBytes_ > 0 ? static_cast<size_t>(streamFrameBytes_) : 4;

    const bool sourceMatchesStream = static_cast<PcmFormat>(streamFormat_) == sourceFormat_ &&
                                     streamChannels_ == sourceChannels_;
    // Camino directo: mismo formato, mismo rate y ganancia exactamente 1.0. No se toca
    // ni un bit; cualquier otra cosa (rampa pendiente o volumen < 100 %) va por el
    // camino general, que aplica la ganancia una sola vez.
    const bool plainCopy = sourceMatchesStream && !resampling_ &&
                           targetGain() >= 1.0f && gain_ >= 1.0f && gainStep_ == 0.0f;

    if (plainCopy) {
        const size_t frames = byteCount / frameBytes;
        if (frames == 0) return 0;
        const size_t want = frames * frameBytes;
        const size_t fit = std::min(want, ring_.freeSpace() / frameBytes * frameBytes);
        if (fit == 0) return 0;
        const size_t written = ring_.write(data, fit);
        framesQueued_.fetch_add(static_cast<int64_t>(written / frameBytes),
                                std::memory_order_relaxed);
        return written;
    }

    // Camino con transformación. Regla de oro del contrato de Media3: nunca consumir
    // entrada que no se pueda procesar y contabilizar ya mismo.
    const int32_t srcFrameBytes = bytesPerSample(sourceFormat_) * sourceChannels_;
    if (srcFrameBytes <= 0) return 0;
    const size_t srcFrameBytesSz = static_cast<size_t>(srcFrameBytes);

    const size_t freeFrames = ring_.freeSpace() / frameBytes;
    if (freeFrames <= 2) return 0;

    size_t inFrames = byteCount / srcFrameBytesSz;
    if (inFrames == 0) return 0;

    const double step = resampling_ ? resampler_.step() : 1.0;
    const size_t allowedIn = static_cast<size_t>(static_cast<double>(freeFrames - 2) / step);
    if (allowedIn == 0) return 0;
    inFrames = std::min(inFrames, allowedIn);
    const size_t inBytes = inFrames * srcFrameBytesSz;

    const PcmFormat outFormat = static_cast<PcmFormat>(streamFormat_);
    const int32_t outSampleBytes = bytesPerSample(outFormat);
    const int32_t maxOut =
            static_cast<int32_t>(static_cast<double>(inFrames) / (step > 0.0 ? step : 1.0)) + 32;
    if (static_cast<int64_t>(floats_.size()) <
        static_cast<int64_t>(inFrames) * sourceChannels_) {
        floats_.resize(inFrames * static_cast<size_t>(sourceChannels_));
    }
    const int32_t inSamples = static_cast<int32_t>(inFrames) * sourceChannels_;
    decodeSamples(data, inSamples, sourceFormat_, floats_.data());

    // Ganancia (volumen digital): se aplica acá, una sola vez, con rampa por muestra
    // para que un cambio de volumen o una pausa no produzca un escalón.
    const float target = targetGain();
    gainStep_ = 0.0f;
    if (target < 1.0f || gain_ != target) {
        const float start = gain_;
        const float stepGain = inFrames > 1
                ? (target - start) / static_cast<float>(inFrames - 1)
                : 0.0f;
        float g = start;
        for (size_t f = 0; f < inFrames; ++f) {
            float* frame = floats_.data() + f * static_cast<size_t>(sourceChannels_);
            for (int32_t c = 0; c < sourceChannels_; ++c) frame[c] *= g;
            g += stepGain;
        }
        gain_ = target;
    }

    // Mapeo de canales si el stream abrió con otro número.
    const float* source = floats_.data();
    if (sourceChannels_ != streamChannels_) {
        if (mapped_.size() < inFrames * static_cast<size_t>(streamChannels_)) {
            mapped_.resize(inFrames * static_cast<size_t>(streamChannels_));
        }
        for (size_t i = 0; i < inFrames; ++i) {
            for (int32_t c = 0; c < streamChannels_; ++c) {
                float v = 0.0f;
                if (c < sourceChannels_) {
                    v = source[i * sourceChannels_ + c];
                } else if (sourceChannels_ == 1) {
                    v = source[i];          // mono replicado: misma señal
                }
                mapped_[i * streamChannels_ + c] = v;
            }
        }
        source = mapped_.data();
    }

    const float* finalFloat = source;
    int32_t outFrames = static_cast<int32_t>(inFrames);
    if (resampling_) {
        if (resampled_.size() < static_cast<size_t>(maxOut) * streamChannels_) {
            resampled_.resize(static_cast<size_t>(maxOut) * streamChannels_);
        }
        outFrames = resampler_.process(source, static_cast<int32_t>(inFrames),
                                       resampled_.data(), maxOut);
        finalFloat = resampled_.data();
    }

    if (outFrames > 0) {
        const size_t outBytes = static_cast<size_t>(outFrames) *
                                static_cast<size_t>(streamChannels_) *
                                static_cast<size_t>(outSampleBytes);
        if (staged_.size() < outBytes) staged_.resize(outBytes);
        encodeSamples(finalFloat, outFrames * streamChannels_, outFormat, staged_.data());
        const size_t written = std::min(outBytes, ring_.freeSpace() / frameBytes * frameBytes);
        const size_t queued = written > 0 ? ring_.write(staged_.data(), written) : 0;
        framesQueued_.fetch_add(static_cast<int64_t>(queued / frameBytes),
                                std::memory_order_relaxed);
        if (queued < outBytes) {
            framesDropped_.fetch_add(static_cast<int64_t>((outBytes - queued) / frameBytes),
                                     std::memory_order_relaxed);
        }
    }
    return inBytes;
}

// ---------------------------------------------------------------------------
// Callback de audio (hilo del HAL) — la parte crítica
// ---------------------------------------------------------------------------

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* /*stream*/,
                                                   void* audioData,
                                                   int32_t numFrames) {
    auto* out = static_cast<uint8_t*>(audioData);
    const size_t frameBytes =
            static_cast<size_t>(streamFrameBytes_ > 0 ? streamFrameBytes_ : 4);
    const size_t byteCount = static_cast<size_t>(numFrames) * frameBytes;

    // 1) Peticiones del hilo de control (acá no se toman candados jamás).
    const int32_t request = fadeRequest_.exchange(kFadeNone, std::memory_order_acquire);
    const bool fades = fades_.load(std::memory_order_relaxed);
    switch (request) {
        case kFadeIn: {
            holding_.store(false, std::memory_order_relaxed);
            // La rampa sube hasta el VOLUMEN ELEGIDO, no hasta 1.0: subir a 1.0 y
            // después bajar al volumen real es un salto de nivel audible (era el
            // "volumen automático" que se escuchaba al empezar cada pista).
            const float fadeTarget = targetGain();
            if (fades && gain_ < fadeTarget) {
                const int32_t frames = std::max(1, fadeInFrames_.load(std::memory_order_relaxed));
                gainStep_ = (fadeTarget - gain_) / static_cast<float>(frames);
            } else {
                gain_ = fadeTarget;
                gainStep_ = 0.0f;
            }
            break;
        }
        case kFadeOutAndHold:
            if (fades && gain_ > 0.0f) {
                const int32_t frames = std::max(1, fadeOutFrames_.load(std::memory_order_relaxed));
                gainStep_ = -gain_ / static_cast<float>(frames);
                stopAfterFade_ = true;
            } else {
                gain_ = 0.0f;
                gainStep_ = 0.0f;
                holding_.store(true, std::memory_order_relaxed);
                fillSilence(out, numFrames);
                return oboe::DataCallbackResult::Stop;
            }
            break;
        case kFadeRearm:
            if (fades) {
                gain_ = 0.0f;
                const int32_t frames = std::max(1, rearmFrames_.load(std::memory_order_relaxed));
                gainStep_ = targetGain() / static_cast<float>(frames);
            } else {
                gain_ = targetGain();
                gainStep_ = 0.0f;
            }
            break;
        default:
            break;
    }

    // 2) Pausa declarada (holding): es el ÚNICO caso en que se devuelve Stop sin fin de
    //    stream. Un ring vacío transitorio NO para el HAL: parar y arrancar tiraba lo
    //    que todavía no había sonado y se escuchaba como cortes.
    if (holding_.load(std::memory_order_relaxed)) {
        if (!endOfStream_.load(std::memory_order_acquire) && ring_.availableToRead() > 0 &&
            !startPending_.load(std::memory_order_acquire)) {
            holding_.store(false, std::memory_order_relaxed);
        } else {
            fillSilence(out, numFrames);
            return oboe::DataCallbackResult::Stop;
        }
    }

    // 3) Copia pura de lo encolado.
    const size_t got = ring_.read(out, byteCount);
    if (got < byteCount) {
        std::memset(out + got, 0, byteCount - got);
        if (!stopAfterFade_) underruns_.fetch_add(1, std::memory_order_relaxed);
    }
    const int32_t gotFrames = static_cast<int32_t>(got / frameBytes);

    // 4) Rampa de ganancia sobre las muestras ya copiadas. Si la ganancia es 1.0 y no
    //    hay rampa, esto no toca ni un byte (bit-perfect).
    if (gotFrames > 0) applyGainRamp(out, gotFrames);
    framesConsumed_.fetch_add(std::max(0, gotFrames), std::memory_order_relaxed);

    // 5) Parada decidida por el callback.
    const bool ringEmpty = ring_.availableToRead() == 0;
    if (stopAfterFade_) {
        if (gain_ <= 0.0f || ringEmpty) {
            gain_ = 0.0f;
            gainStep_ = 0.0f;
            stopAfterFade_ = false;
            holding_.store(true, std::memory_order_relaxed);
            return oboe::DataCallbackResult::Stop;
        }
        return oboe::DataCallbackResult::Continue;
    }
    if (ringEmpty && endOfStream_.load(std::memory_order_acquire)) {
        // Fin de stream de verdad: no queda nada que el DAC deba escuchar.
        holding_.store(true, std::memory_order_relaxed);
        return oboe::DataCallbackResult::Stop;
    }
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::fillSilence(uint8_t* data, int32_t frames) const {
    if (data == nullptr || frames <= 0) return;
    // 0x00 es muestra 0 en I16, I24, I32 y F32.
    std::memset(data, 0,
                static_cast<size_t>(frames) *
                        static_cast<size_t>(streamFrameBytes_ > 0 ? streamFrameBytes_ : 4));
}

void AudioEngine::applyGainRamp(uint8_t* data, int32_t frames) {
    const int32_t ch = streamChannels_;
    if (data == nullptr || frames <= 0 || ch <= 0) return;
    const float target = targetGain();
    if (gain_ == target && gainStep_ == 0.0f) return;   // camino bit-perfect intacto

    // Paso de la rampa: el que pidió el control (fade) o, si no hay, el limitado por
    // tiempo (~20 ms por cambio completo). Nunca se da un escalón de amplitud.
    float step = gainStep_;
    if (step == 0.0f) {
        const float limit = 1.0f / (kGainRampSeconds * static_cast<float>(streamRate_));
        const float delta = target - gain_;
        step = (std::fabs(delta) <= limit * static_cast<float>(frames))
                       ? delta / static_cast<float>(frames)
                       : (delta > 0.0f ? limit : -limit);
    }
    // El destino de la rampa NO es siempre el volumen: un fundido de salida va a 0 y
    // uno de entrada a 1. Confundirlos (recortar contra `target`) hacía que la pausa
    // nunca bajara el nivel y el stream no se detuviera.
    float rampTarget = target;
    if (stopAfterFade_) rampTarget = 0.0f;

    float g = gain_;
    const auto advance = [&]() {
        g += step;
        if ((step > 0.0f && g > rampTarget) || (step < 0.0f && g < rampTarget)) g = rampTarget;
        if (g > 1.0f) g = 1.0f;
        if (g < 0.0f) g = 0.0f;
    };

    switch (static_cast<PcmFormat>(streamFormat_)) {
        case PcmFormat::kI16: {
            auto* samples = reinterpret_cast<int16_t*>(data);
            for (int32_t f = 0; f < frames; ++f) {
                int16_t* frame = samples + static_cast<size_t>(f) * ch;
                for (int32_t c = 0; c < ch; ++c) {
                    frame[c] = static_cast<int16_t>(
                            std::lrint(static_cast<float>(frame[c]) * g));
                }
                advance();
            }
            break;
        }
        case PcmFormat::kF32: {
            auto* samples = reinterpret_cast<float*>(data);
            for (int32_t f = 0; f < frames; ++f) {
                float* frame = samples + static_cast<size_t>(f) * ch;
                for (int32_t c = 0; c < ch; ++c) frame[c] *= g;
                advance();
            }
            break;
        }
        case PcmFormat::kI32: {
            auto* samples = reinterpret_cast<int32_t*>(data);
            for (int32_t f = 0; f < frames; ++f) {
                int32_t* frame = samples + static_cast<size_t>(f) * ch;
                for (int32_t c = 0; c < ch; ++c) {
                    double v = static_cast<double>(frame[c]) * static_cast<double>(g);
                    if (v > 2147483647.0) v = 2147483647.0;
                    if (v < -2147483648.0) v = -2147483648.0;
                    frame[c] = static_cast<int32_t>(std::lrint(v));
                }
                advance();
            }
            break;
        }
        case PcmFormat::kI24: {
            for (int32_t f = 0; f < frames; ++f) {
                uint8_t* frame = data + static_cast<size_t>(f) * ch * 3;
                for (int32_t c = 0; c < ch; ++c) {
                    uint8_t* b = frame + static_cast<size_t>(c) * 3;
                    int32_t v = static_cast<int32_t>(
                            static_cast<uint32_t>(b[0]) |
                            (static_cast<uint32_t>(b[1]) << 8) |
                            (static_cast<uint32_t>(b[2]) << 16));
                    if (v & 0x800000) v |= ~0xFFFFFF;   // extender signo de 24 bits
                    double scaled = static_cast<double>(v) * static_cast<double>(g);
                    if (scaled > 8388607.0) scaled = 8388607.0;
                    if (scaled < -8388608.0) scaled = -8388608.0;
                    const int32_t s24 = static_cast<int32_t>(std::lrint(scaled)) & 0xFFFFFF;
                    b[0] = static_cast<uint8_t>(s24 & 0xFF);
                    b[1] = static_cast<uint8_t>((s24 >> 8) & 0xFF);
                    b[2] = static_cast<uint8_t>((s24 >> 16) & 0xFF);
                }
                advance();
            }
            break;
        }
    }

    gain_ = g;
    // Si la rampa llegó al destino se corta: la ganancia queda exacta y el próximo
    // bloque vuelve al camino directo (o el callback para, si era un fundido de salida).
    if (gain_ == rampTarget) gainStep_ = 0.0f;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    logE("stream error after close: %s (posible DAC desconectado)", oboe::convertToText(error));
    errorCode_.store(static_cast<int32_t>(error), std::memory_order_relaxed);
    holding_.store(true, std::memory_order_relaxed);
    startPending_.store(false, std::memory_order_relaxed);
}

} // namespace audio
