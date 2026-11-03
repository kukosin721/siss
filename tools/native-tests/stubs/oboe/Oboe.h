#pragma once
// Dobles de host para compilar y probar `audio/AudioEngine.cpp` fuera de Android.
// NO son Oboe: reproducen el contrato que documenta Oboe 1.11 y permiten inyectar
// el reloj de hardware (timestamps) para verificar la posición de playout.

#include <algorithm>
#include <chrono>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <initializer_list>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

typedef int clockid_t;
#ifndef CLOCK_MONOTONIC
#define CLOCK_MONOTONIC 1
#endif

namespace oboe {

enum class Result : int32_t {
    OK = 0,
    ErrorDisconnected = -899,
    ErrorIllegalArgument = -898,
    ErrorInvalidState = -895,
    ErrorUnavailable = -892,
    ErrorNoMemory = -890,
    ErrorNull = -889,
    ErrorInvalidFormat = -886,
    ErrorOutOfRange = -885,
    ErrorInvalidRate = -883,
    ErrorClosed = -882,
    ErrorTimeout = -881,
    ErrorUnimplemented = -880,
};

enum class AudioFormat : int32_t { Invalid = -1, Unspecified = 0, I16 = 1, Float = 2, I24 = 3, I32 = 4 };
enum class SharingMode : int32_t { Exclusive = 0, Shared = 1 };
enum class PerformanceMode : int32_t { None = 1, LowLatency = 2 };
enum class AudioApi : int32_t { Unspecified = 0, OpenSLES = 1, AAudio = 2 };
enum class Direction : int32_t { Output = 0, Input = 1 };
enum class Usage : int32_t { Media = 1 };
enum class ContentType : int32_t { Music = 2 };
enum class SpatializationBehavior : int32_t { Never = 2 };
enum class SampleRateConversionQuality : int32_t { None = 0, Fastest = 1 };
enum class StreamState : int32_t {
    Uninitialized = 0,
    Unknown = 1,
    Open = 2,
    Starting = 3,
    Started = 4,
    Pausing = 5,
    Paused = 6,
    Flushing = 7,
    Flushed = 8,
    Stopping = 9,
    Stopped = 10,
    Closing = 11,
    Closed = 12,
    Disconnected = 13,
};
enum class DataCallbackResult : int32_t { Continue = 0, Stop = 1, Start = 2 };
struct ChannelCount { static constexpr int32_t Stereo = 2; };

inline const char* convertToText(Result result) {
    switch (result) {
        case Result::OK: return "OK";
        case Result::ErrorInvalidState: return "InvalidState";
        case Result::ErrorUnavailable: return "Unavailable";
        case Result::ErrorTimeout: return "Timeout";
        default: return "Error";
    }
}

template <class T>
struct ResultWithValue {
    T value_{};
    Result error_ = Result::OK;
    ResultWithValue() = default;
    explicit ResultWithValue(Result error) : error_(error) {}
    explicit ResultWithValue(T value) : value_(value) {}
    T value() const { return value_; }
    Result error() const { return error_; }
};

class AudioStream;

class AudioStreamDataCallback {
public:
    virtual ~AudioStreamDataCallback() = default;
    virtual DataCallbackResult onAudioReady(AudioStream* stream, void* audioData,
                                             int32_t numFrames) = 0;
};

class AudioStreamErrorCallback {
public:
    virtual ~AudioStreamErrorCallback() = default;
    virtual void onErrorAfterClose(AudioStream* stream, Result error) {
        (void) stream;
        (void) error;
    }
};

/**
 * Stream simulado. El dueño del test puede "tirar" del callback con
 * `renderFrames()` y avanzar el reloj de hardware con `advanceHardware()`.
 */
class AudioStream {
public:
    // --- configuración del doble ---
    AudioFormat format_ = AudioFormat::I16;
    int32_t sampleRate_ = 48000;
    int32_t channelCount_ = 2;
    int32_t framesPerBurst_ = 192;
    int32_t bufferCapacity_ = 4096;
    int32_t bufferSize_ = 384;
    SharingMode sharingMode_ = SharingMode::Exclusive;
    AudioApi audioApi_ = AudioApi::AAudio;
    int32_t deviceId_ = 0;
    AudioStreamDataCallback* dataCallback_ = nullptr;
    AudioStreamErrorCallback* errorCallback_ = nullptr;
    int32_t openResult_ = 0;   // != 0 => openStream falla con este código

    // --- estado observable ---
    std::atomic<StreamState> state_{StreamState::Open};
    std::atomic<int64_t> framesWritten_{0};
    std::atomic<int64_t> framesRead_{0};
    std::atomic<int64_t> hardwareLatencyFrames_{0};  // frames "en vuelo" hacia el DAC
    std::atomic<int32_t> flushCalls_{0};
    std::atomic<int32_t> startCalls_{0};
    std::atomic<int32_t> stopCalls_{0};
    std::atomic<int32_t> invalidFlushCalls_{0};      // flush fuera de estado válido

    AudioFormat getFormat() const { return format_; }
    int32_t getSampleRate() const { return sampleRate_; }
    int32_t getChannelCount() const { return channelCount_; }
    int32_t getFramesPerBurst() const { return framesPerBurst_; }
    int32_t getBufferCapacityInFrames() const { return bufferCapacity_; }
    int32_t getBufferSizeInFrames() const { return bufferSize_; }
    SharingMode getSharingMode() const { return sharingMode_; }
    AudioApi getAudioApi() const { return audioApi_; }
    int32_t getDeviceId() const { return deviceId_; }
    StreamState getState() const { return state_.load(); }

    ResultWithValue<int32_t> setBufferSizeInFrames(int32_t frames) {
        bufferSize_ = std::min(frames, bufferCapacity_);
        return ResultWithValue<int32_t>(bufferSize_);
    }
    ResultWithValue<int32_t> getXRunCount() const { return ResultWithValue<int32_t>(0); }
    int64_t getFramesWritten() { return framesWritten_.load(); }
    int64_t getFramesRead() { return framesRead_.load(); }

    Result requestStart() {
        startCalls_.fetch_add(1);
        state_.store(StreamState::Started);
        return Result::OK;
    }
    Result requestPause() {
        state_.store(StreamState::Paused);
        return Result::OK;
    }
    Result requestStop() {
        stopCalls_.fetch_add(1);
        state_.store(StreamState::Stopped);
        return Result::OK;
    }
    Result requestFlush() {
        flushCalls_.fetch_add(1);
        // Regla de AAudio: sólo OPEN/PAUSED/STOPPED/FLUSHED. En otro estado no hace nada.
        const StreamState current = state_.load();
        if (current != StreamState::Open && current != StreamState::Paused &&
            current != StreamState::Stopped && current != StreamState::Flushed) {
            invalidFlushCalls_.fetch_add(1);
            return Result::ErrorInvalidState;
        }
        state_.store(StreamState::Flushed);
        return Result::OK;
    }
    Result close() {
        state_.store(StreamState::Closed);
        return Result::OK;
    }
    // Firma real de Oboe: waitForStateChange(inputState, nextState*, timeoutNs).
    Result waitForStateChange(StreamState /*inputState*/, StreamState* nextState,
                              int64_t /*timeoutNanoseconds*/) {
        if (nextState) *nextState = state_.load();
        return Result::ErrorTimeout;   // el doble cambia de estado de forma inmediata
    }

    /** Timestamp de hardware: posición = frames leídos - latencia en vuelo. */
    Result getTimestamp(clockid_t /*clockId*/, int64_t* framePosition, int64_t* timeNanos) {
        const int64_t latency = hardwareLatencyFrames_.load();
        const int64_t position = framesRead_.load() - latency;
        *framePosition = std::max<int64_t>(0, position);
        // El HAL real entrega un instante de CLOCK_MONOTONIC. Devolver 0 hacía que la
        // extrapolación entre timestamps nunca corriera (y que ningún test la cubriera).
        *timeNanos = std::chrono::duration_cast<std::chrono::nanoseconds>(
                             std::chrono::steady_clock::now().time_since_epoch())
                             .count();
        return Result::OK;
    }
    void advanceHardware(int64_t frames) { framesRead_.fetch_add(frames); }

    /** Simula un bloque de audio del HAL. Devuelve lo que pidió el callback. */
    DataCallbackResult renderFrames(int32_t numFrames) {
        if (state_.load() != StreamState::Started || dataCallback_ == nullptr) {
            return DataCallbackResult::Stop;
        }
        const int32_t sampleBytes = format_ == AudioFormat::I16   ? 2
                                    : format_ == AudioFormat::I24 ? 3
                                                                  : 4;
        const int32_t bytes = numFrames * channelCount_ * sampleBytes;
        std::vector<uint8_t> buffer(static_cast<size_t>(bytes), 0);
        const DataCallbackResult result = dataCallback_->onAudioReady(this, buffer.data(), numFrames);
        lastBuffer_ = std::move(buffer);
        lastFrames_ = numFrames;
        if (result == DataCallbackResult::Stop) {
            state_.store(StreamState::Stopped);
            stopCalls_.fetch_add(1);
        } else if (result == DataCallbackResult::Start) {
            state_.store(StreamState::Started);
        }
        return result;
    }
    const std::vector<uint8_t>& lastBuffer() const { return lastBuffer_; }
    int32_t lastFrames() const { return lastFrames_; }

    void fireErrorAfterClose(Result error) {
        if (errorCallback_ != nullptr) errorCallback_->onErrorAfterClose(this, error);
    }

    // --- fábrica ---
    static std::shared_ptr<AudioStream> create() { return std::make_shared<AudioStream>(); }

private:
    std::vector<uint8_t> lastBuffer_;
    int32_t lastFrames_ = 0;
};

class AudioStreamBuilder {
public:
    AudioStreamBuilder& setDirection(Direction) { return *this; }
    AudioStreamBuilder& setAudioApi(AudioApi) { return *this; }
    AudioStreamBuilder& setFormat(AudioFormat format) { format_ = format; return *this; }
    AudioStreamBuilder& setSampleRate(int32_t rate) { sampleRate_ = rate; return *this; }
    AudioStreamBuilder& setChannelCount(int32_t channels) { channels_ = channels; return *this; }
    AudioStreamBuilder& setSharingMode(SharingMode mode) { sharing_ = mode; return *this; }
    AudioStreamBuilder& setPerformanceMode(PerformanceMode mode) { performance_ = mode; return *this; }
    AudioStreamBuilder& setUsage(Usage) { return *this; }
    AudioStreamBuilder& setContentType(ContentType) { return *this; }
    AudioStreamBuilder& setSpatializationBehavior(SpatializationBehavior) { return *this; }
    AudioStreamBuilder& setIsContentSpatialized(bool) { return *this; }
    AudioStreamBuilder& setFormatConversionAllowed(bool) { return *this; }
    AudioStreamBuilder& setChannelConversionAllowed(bool) { return *this; }
    AudioStreamBuilder& setSampleRateConversionQuality(SampleRateConversionQuality) { return *this; }
    AudioStreamBuilder& setDataCallback(AudioStreamDataCallback* cb) { dataCallback_ = cb; return *this; }
    AudioStreamBuilder& setErrorCallback(AudioStreamErrorCallback* cb) { errorCallback_ = cb; return *this; }
    AudioStreamBuilder& setDeviceId(int32_t deviceId) { deviceId_ = deviceId; return *this; }

    Result openStream(std::shared_ptr<AudioStream>& stream) {
        // El doble "dispositivo": anuncia los formatos que soporta. Por defecto acepta
        // todo lo que se le pide, salvo que el test configure `unsupportedRate_`.
        if (unsupportedRate_ != 0 && sampleRate_ == unsupportedRate_) {
            return Result::ErrorInvalidRate;
        }
        if (requireExclusive_ && sharing_ == SharingMode::Shared) {
            return Result::ErrorUnavailable;
        }
        auto created = std::make_shared<AudioStream>();
        created->format_ = format_;
        created->sampleRate_ = forcedRate_ > 0 ? forcedRate_ : sampleRate_;
        created->channelCount_ = channels_;
        created->sharingMode_ = sharing_;
        created->deviceId_ = deviceId_;
        created->dataCallback_ = dataCallback_;
        created->errorCallback_ = errorCallback_;
        lastOpened_ = created;
        openCount_.fetch_add(1);
        stream = created;
        return Result::OK;
    }

    // Ajustes globales que usa el test (no existen en Oboe; sólo en el doble).
    /** Fuerza el rate que devuelve el stream (simula un HAL que ignora/admite otro). */
    static void setForcedOutputRate(int32_t rate) { forcedRate_ = rate; }
    static void setUnsupportedRate(int32_t rate) { unsupportedRate_ = rate; }
    static void setRequireExclusive(bool required) { requireExclusive_ = required; }
    static std::shared_ptr<AudioStream> lastOpened() { return lastOpened_; }
    static int32_t openCount() { return openCount_.load(); }
    static void reset() {
        unsupportedRate_ = 0;
        forcedRate_ = 0;
        requireExclusive_ = false;
        lastOpened_.reset();
        openCount_.store(0);
    }

private:
    AudioFormat format_ = AudioFormat::I16;
    int32_t sampleRate_ = 48000;
    int32_t channels_ = 2;
    SharingMode sharing_ = SharingMode::Exclusive;
    PerformanceMode performance_ = PerformanceMode::None;
    int32_t deviceId_ = 0;
    AudioStreamDataCallback* dataCallback_ = nullptr;
    AudioStreamErrorCallback* errorCallback_ = nullptr;

    static inline int32_t unsupportedRate_ = 0;
    static inline int32_t forcedRate_ = 0;
    static inline bool requireExclusive_ = false;
    static inline std::shared_ptr<AudioStream> lastOpened_{};
    static inline std::atomic<int32_t> openCount_{0};
};

} // namespace oboe
