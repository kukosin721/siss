#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include <oboe/Oboe.h>

#include "audio/Resampler.h"

#if defined(__has_include)
#  if __has_include(<oboe/OboeExtensions.h>)
#    include <oboe/OboeExtensions.h>
#    define AUDIOENGINE_HAVE_OBOE_EXTENSIONS 1
#  endif
#endif

namespace audio {

//
// Motor de salida sobre Oboe (AAudio / OpenSL ES). Reescrito desde cero.
//
// Los seis principios de este archivo, cada uno atado a un síntoma real:
//
//  1. EL VOLUMEN NUNCA DA UN ESCALÓN. La ganancia no se recalcula por bloque: el
//     callback interpola hacia el objetivo con un paso limitado en tiempo (~20 ms por
//     cambio completo). Mover el deslizador o cambiar de pista no puede producir un
//     salto de amplitud, que es lo que suena a "volumen automático".
//
//  2. EL RELOJ NO PUEDE IR POR DETRÁS DEL SONIDO. La posición de playout sale de los
//     frames realmente entregados al HAL y se reconcilia con el timestamp. Informar
//     MENOS posición de la real hace que Media3 alimente más despacio de lo que el DAC
//     consume: eso es bajo flujo ("buffer underrun") periódico, o sea el corte a cada
//     rato. Por eso la latencia estimada nunca subestima la real y la posición tiene
//     una cota inferior monótona.
//
//  3. EL CAMINO DIRECTO ES UN memcpy. Si el stream quedó en el formato de la pista y
//     la ganancia es 1.0, los bytes van al anillo tal cual, sin pasar por float. La
//     re-cuantización por bloque era la causa del "suena saturado".
//
//  4. NO HAY CONVERSIÓN ESCONDIDA. El motor abre el stream en el formato y rate de la
//     pista o en el rate nativo del equipo. Convierte una sola vez por stream (en el
//     productor, con estado privado) y lo informa; nunca convierte "de yapa".
//
//  5. UN RING VACÍO NO PARA EL HAL. El callback sólo devuelve Stop cuando el stream
//     está pausado de verdad o cuando el fin de stream terminó de drenar. Parar y
//     arrancar el HAL por hambre tiraba lo que aún no había sonado.
//
//  6. TODO LO QUE SE INFORMA ES VERIFICABLE: exclusivo, MMAP, conversión del HAL y
//     latencia salen de lo que el hardware devolvió, no de lo que se pidió.
//

/** Formato PCM. Los códigos son parte del protocolo JNI: no reordenar. */
enum class PcmFormat : int32_t {
    kI16 = 0,
    kI24 = 1,   // 24 bits empaquetado en 3 bytes (AAUDIO_FORMAT_PCM_I24_PACKED)
    kF32 = 2,
    kI32 = 3,
};

int32_t bytesPerSample(PcmFormat format);
const char* formatName(PcmFormat format);

/** Lo que pide la app: el formato REAL de la pista decodificada. */
struct EngineConfig {
    PcmFormat sourceFormat = PcmFormat::kI16;
    int32_t sourceSampleRate = 0;   // 0 = no definido
    int32_t sourceChannels = 2;
    int32_t deviceId = 0;           // 0 = dispositivo por defecto
    /** Si true, el motor no acepta conversión: o abre exacto, o falla y lo informa. */
    bool strictBitPerfect = false;
    /** Si true, no se acepta un stream compartido (modos exclusivos). */
    bool requireExclusive = false;
    /**
     * Si true, el motor NO cae al dispositivo por defecto cuando `deviceId` no se
     * puede abrir. Obligatorio en los modos que apuntan a un DAC USB: preferimos
     * fallar y que la UI lo muestre antes que mandar la señal a otro altavoz.
     */
    bool requireDevice = false;
    /** Objetivo del anillo: la reserva que absorbe los tirones del decoder. */
    int32_t bufferMs = 250;
    /** Rampas de ~20 ms al arrancar/parar/pausar. Apagado = señal intacta. */
    bool fades = true;
    /** Preroll del arranque en ms (-1 = automático: media ráfaga). */
    int32_t prerollMs = -1;
    /** 0 = lineal, 1 = perceptual (v^3). A 100 % ambas dan exactamente 1.0. */
    int32_t volumeCurve = 0;
};

/** Lo que devolvió el hardware. La UI muestra esto tal cual. */
struct StreamInfo {
    int32_t api = -1;            // 0 = AAudio, 1 = OpenSL ES
    int32_t format = -1;         // PcmFormat real del stream
    int32_t sampleRate = 0;      // rate con el que se escribe
    int32_t channels = 0;
    int32_t sourceFormat = -1;   // formato de la pista
    int32_t sourceSampleRate = 0;
    int32_t sourceChannels = 0;
    int32_t volumePercent = 100;
    int32_t framesPerBurst = 0;
    int32_t bufferFrames = 0;      // buffer del HAL (lo que se descarta en un flush)
    int32_t ringFrames = 0;        // anillo del motor: el colchón real contra los cortes
    int32_t deviceId = 0;
    int32_t xRuns = -1;          // -1 = no reportado por la API
    int32_t errorCode = 0;       // último error nativo (0 = ninguno)
    int32_t latencyMicros = -1;  // latencia real medida (buffer del HAL)
    int32_t underruns = 0;       // veces que el callback se quedó sin datos
    bool mmap = false;           // el stream usa el camino MMAP (sin mezclador)
    bool exclusive = false;
    bool bitPerfect = false;     // exclusivo + mismo formato + mismo rate + ganancia 1
    bool passthrough = false;    // el write path es memcpy
    bool halConverted = false;   // el HAL impuso otro rate o formato
};

/** Anillo lock-free SPSC de bytes PCM (contenido ya en el formato del stream). */
class ByteRingBuffer {
public:
    ByteRingBuffer();
    ByteRingBuffer(const ByteRingBuffer&) = delete;
    ByteRingBuffer& operator=(const ByteRingBuffer&) = delete;

    void resize(size_t capacity);   // sólo sin stream abierto
    size_t write(const uint8_t* data, size_t count);
    size_t read(uint8_t* out, size_t count);
    void clear();
    size_t availableToRead() const;
    size_t freeSpace() const;
    size_t capacity() const { return capacity_; }

private:
    size_t capacity_ = 0;
    size_t mask_ = 0;
    std::vector<uint8_t> buffer_;
    std::atomic<size_t> readIndex_{0};
    std::atomic<size_t> writeIndex_{0};
};

/**
 * Motor de salida.
 *
 * Contrato con Media3 (`AudioOutput`):
 *
 *  - `write()` devuelve cuántos bytes de ORIGEN aceptó; si el anillo está lleno
 *    devuelve 0 y Media3 reintenta el MISMO buffer. Nunca se pierde audio.
 *  - `stop()` = "deja sonar lo que queda y para" (`playToEndOfStream`).
 *  - `flush()` descarta lo pendiente y deja el stream reutilizable (PAUSED).
 *  - `positionMicros()` es la posición de PLAYOUT: lo que ya sonó, no lo copiado.
 */
class AudioEngine final : public oboe::AudioStreamDataCallback,
                          public oboe::AudioStreamErrorCallback {
public:
    AudioEngine();
    ~AudioEngine() override;

    AudioEngine(const AudioEngine&) = delete;
    AudioEngine& operator=(const AudioEngine&) = delete;

    oboe::Result open(const EngineConfig& config);
    oboe::Result close();

    oboe::Result start();
    oboe::Result stop();     // fin de stream: drena y se detiene solo
    oboe::Result pause();    // rampa corta y se detiene (los datos quedan)
    oboe::Result flush();    // descarta lo pendiente (seek / cambio de pista)
    /**
     * Como `flush()`, pero sin esperar las transiciones de estado del HAL: entre dos
     * pistas cada milisegundo de espera es silencio audible.
     */
    oboe::Result flushForTransition();

    /** Curva de volumen: 0 = lineal, 1 = perceptual (v^3). */
    void setVolumeCurve(int32_t curve);
    /** Volumen 0..1. Se aplica con rampa en el callback, nunca como escalón. */
    void setVolume(float volume);
    void setFadesEnabled(bool enabled);

    /** Espera a que el DAC consuma lo encolado. Devuelve true si drenó. */
    bool drain(int32_t timeoutMs);

    /** Bytes de origen aceptados (0 = reintentar más tarde). */
    size_t write(const uint8_t* data, size_t byteCount);

    StreamInfo info() const;

    /** Posición de playout en frames del stream (monótona dentro del stream). */
    int64_t positionFrames();
    /** Posición de playout en microsegundos (dominio temporal de la pista). */
    int64_t positionMicros();
    /** Latencia estimada en microsegundos (-1 = desconocida). */
    int64_t latencyMicros();
    /** Frames de origen encolados desde el último flush/close. */
    int64_t sourceFramesQueued() const;
    /** Frames consumidos por el callback desde el último flush/close. */
    int64_t framesConsumed() const { return framesConsumed_.load(std::memory_order_relaxed); }
    /** true si el callback ya drenó todo lo encolado (fin de stream real). */
    bool endOfStreamReached() const;

    oboe::StreamState state() const;

    /** Espera (con timeout) a que termine la apertura diferida. */
    bool awaitOpen(int32_t timeoutMs = 2000);

    int32_t lastErrorCode() const { return errorCode_.load(std::memory_order_relaxed); }
    bool isOpen() const { return stream_ != nullptr; }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream,
                                          void* audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    enum FadeRequest : int32_t {
        kFadeNone = 0,
        kFadeIn = 1,
        kFadeOutAndHold = 2,
        kFadeRearm = 3,
    };

    /** Cuerpo de close() sin tomar el mutex (open() ya lo tiene tomado). */
    void closeLocked();

    void openThreadMain();
    void joinOpenThread();

    /**
     * Abre un stream con formato/rate/canales explícitos. `nativeRate` es lo que se le
     * pide al HAL; si difiere de `streamRate`, el motor convierte el rate por su cuenta
     * (una sola vez por stream) y lo informa como `passthrough = false`.
     */
    oboe::Result openStream(PcmFormat streamFormat,
                            int32_t streamRate,
                            int32_t nativeRate,
                            int32_t streamChannels,
                            oboe::SharingMode sharing,
                            oboe::PerformanceMode performance);
    oboe::Result tryOpenCandidates(const EngineConfig& config);
    void finalizeOpen(const EngineConfig& config);
    oboe::Result flushInternal(bool waitForHardware);
    /** Ganancia objetivo del volumen actual, con la curva elegida. */
    float targetGain() const;
    oboe::Result setStreamState(int32_t target);
    bool waitForState(std::initializer_list<oboe::StreamState> states, int32_t timeoutMs);

    void resetStreamAccounting();
    void updatePlayoutPosition(bool forceZeroLatency);

    /** Copia al anillo sin tocar las muestras cuando el camino es directo. */
    size_t writeToRing(const uint8_t* data, size_t byteCount);
    /** Aplica la rampa de ganancia sobre las muestras ya copiadas. */
    void applyGainRamp(uint8_t* data, int32_t frames);
    void fillSilence(uint8_t* data, int32_t frames) const;

    oboe::Result maybeStart();

    std::shared_ptr<oboe::AudioStream> stream_;
    mutable std::mutex controlMutex_;

    // Apertura diferida: negociar exclusividad/MMAP puede tardar; nunca en el hilo de
    // audio de Media3.
    std::thread openThread_;
    std::atomic<bool> openPending_{false};
    std::atomic<int32_t> openOutcome_{0};
    EngineConfig pendingConfig_{};

    // Diagnóstico (lo publica info()).
    int32_t apiCode_ = -1;
    int32_t streamFormat_ = -1;
    int32_t streamRate_ = 0;      // rate con el que se ESCRIBE
    int32_t nativeRate_ = 0;      // rate real del stream en el HAL
    int32_t streamChannels_ = 0;
    int32_t streamFrameBytes_ = 4;
    int32_t deviceId_ = 0;
    bool exclusive_ = false;
    bool mmap_ = false;
    bool halConverted_ = false;
    mutable std::atomic<int32_t> errorCode_{0};
    std::atomic<int64_t> underruns_{0};
    std::atomic<int64_t> framesConsumed_{0};
    std::atomic<int64_t> framesQueued_{0};
    std::atomic<int64_t> framesDropped_{0};
    std::atomic<int64_t> lastLatencyFrames_{0};
    std::atomic<int64_t> lastPositionFrames_{0};
    /** Origen del contador del HAL (-1 = todavía no se leyó). */
    std::atomic<int64_t> halPositionBase_{-1};
    std::atomic<int32_t> hardwareBufferFrames_{0};

    /**
     * Ancla del timestamp del HAL: (frames presentados, instante del reloj). Entre
     * refrescos la posición se extrapola con el reloj monótono.
     */
    std::atomic<int64_t> timestampAnchorNanos_{0};
    std::atomic<int64_t> timestampAnchorFrames_{0};

    // Formato de origen
    PcmFormat sourceFormat_ = PcmFormat::kI16;
    int32_t sourceRate_ = 0;
    int32_t sourceChannels_ = 2;

    /** El stream quedó en el formato/rate exactos de la pista. */
    bool passthrough_ = true;
    /** El stream necesita conversión de rate hecha por el motor. */
    bool resampling_ = false;

    /** Volumen y curva elegidos. */
    std::atomic<float> volume_{1.0f};
    std::atomic<int32_t> volumeCurve_{0};

    // Rampas: las pide el hilo de control, las resuelve el callback.
    std::atomic<int32_t> fadeRequest_{kFadeNone};
    std::atomic<bool> holding_{false};
    std::atomic<bool> fades_{true};
    std::atomic<int32_t> fadeInFrames_{0};
    std::atomic<int32_t> fadeOutFrames_{0};
    std::atomic<int32_t> rearmFrames_{0};
    std::atomic<int32_t> prerollFrames_{0};
    std::atomic<bool> startPending_{false};
    std::atomic<bool> endOfStream_{false};

    // Estado del callback (sólo lo toca el callback).
    float gain_ = 1.0f;        // ganancia aplicada
    float gainStep_ = 0.0f;    // incremento por frame de la rampa en curso
    bool stopAfterFade_ = false;
    bool holdingLogged_ = false;

    ByteRingBuffer ring_;

    // Buffers del productor (sólo los toca el hilo que llama write()).
    PolyphaseResampler resampler_;
    std::vector<float> floats_;
    std::vector<float> mapped_;
    std::vector<float> resampled_;
    std::vector<uint8_t> staged_;
};

} // namespace audio
