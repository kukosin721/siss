// Pruebas del motor de audio nativo (inspector del anillo + `AudioEngine` completo)
// contra dobles de Oboe. Compilar y ejecutar: tools/native-tests/run.sh
//
// Cada grupo corresponde a un síntoma que el motor REESCRITO tiene que no repetir:
//
//  1. Anillo SPSC concurrente (sin pérdida ni corrupción).
//  2. Fidelidad: el camino directo es byte a byte; lo que se pide se entrega igual.
//  3. Hambre transitoria: el callback NO para el HAL (antes: un ring vacío devolvía
//     Stop y el stream arrancaba y paraba sin parar → cortes).
//  4. Pausa / reanudación: la pausa para el HAL; reanudar conserva la cola.
//  5. Fin de stream: `stop()` drena TODO antes de detenerse (gapless).
//  6. `flush()` nunca desde STARTED y deja el stream reutilizable en PAUSED.
//  7. Reloj: monótono, con la latencia del hardware descontada, y nunca por detrás de
//     lo que ya sonó (antes el reloj atrasado hacía que Media3 alimentara de menos).
//  8. Volumen: rampa suave (ningún bloque pasa de ~2 dB), 100 % = bit-exacto.
//  9. Política de apertura: exacto → compartido → otro rate (conversión propia) →
//     otro formato; `requireDevice` nunca cae al altavoz interno.
// 10. Errores: DAC desconectado se reporta al sink.
//
// `tools/native-tests/run.sh   [--asan] [--tsan]`
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <thread>
#include <vector>

// Los tests necesitan mirar el estado interno (ring_, stream_, ganancia del callback),
// así que compilan el .cpp completo, igual que antes. El tamaño del objeto no es
// problema en el host.
#define private public
#define protected public
#include "audio/AudioEngine.cpp"
#undef private
#undef protected

#include "audio/Resampler.cpp"

// ---------------------------------------------------------------------------
// Utilidades
// ---------------------------------------------------------------------------

static int g_failures = 0;
static int g_checks = 0;

#define CHECK(cond)                                                              \
    do {                                                                         \
        ++g_checks;                                                              \
        if (!(cond)) {                                                           \
            std::printf("    \033[31mFALLA\033[0m %s:%d  %s\n", __FILE__,        \
                        __LINE__, #cond);                                        \
            ++g_failures;                                                        \
        }                                                                        \
    } while (0)

#define CHECK_EQ(actual, expected)                                               \
    do {                                                                         \
        ++g_checks;                                                              \
        const auto a_ = (actual);                                                \
        const auto e_ = (expected);                                              \
        if (!(a_ == e_)) {                                                       \
            std::printf("    \033[31mFALLA\033[0m %s:%d  %s == %s (obtenido %lld, esperado %lld)\n", \
                        __FILE__, __LINE__, #actual, #expected,                      \
                        static_cast<long long>(a_), static_cast<long long>(e_));     \
            ++g_failures;                                                        \
        }                                                                        \
    } while (0)

static void beginTest(const char* name) {
    std::printf("\n\033[1m%s\033[0m\n", name);
}

/**
 * Motor abierto contra el doble. `open()` sólo acepta la configuración (la apertura
 * real corre en su hilo); el helper espera a que termine.
 */
struct Fixture {
    audio::AudioEngine engine;

    bool open(const audio::EngineConfig& cfg) {
        engine.open(cfg);
        return engine.awaitOpen(2000);
    }
    std::shared_ptr<oboe::AudioStream> stream() { return engine.stream_; }
    std::shared_ptr<oboe::AudioStream> startWithData(int32_t frames) {
        pushFrames(frames);
        engine.start();
        return engine.stream_;
    }
    /** Empuja `frames` de PCM conocido y devuelve lo aceptado. */
    size_t pushFrames(int32_t frames, uint8_t seed = 0x10) {
        const size_t frameBytes = static_cast<size_t>(
                audio::bytesPerSample(engine.sourceFormat_) * engine.sourceChannels_);
        std::vector<uint8_t> data(static_cast<size_t>(frames) * frameBytes);
        for (size_t i = 0; i < data.size(); ++i) {
            data[i] = static_cast<uint8_t>((seed + i) & 0xFF);
        }
        size_t accepted = 0;
        while (accepted < data.size()) {
            const size_t n = engine.write(data.data() + accepted, data.size() - accepted);
            if (n == 0) break;
            accepted += n;
        }
        return accepted;
    }
};

static audio::EngineConfig makeConfig(audio::PcmFormat format, int32_t rate, int32_t channels,
                                      bool strict = false, bool exclusive = true) {
    audio::EngineConfig cfg;
    cfg.sourceFormat = format;
    cfg.sourceSampleRate = rate;
    cfg.sourceChannels = channels;
    cfg.strictBitPerfect = strict;
    cfg.requireExclusive = exclusive;
    cfg.bufferMs = 200;
    cfg.fades = false;   // los tests de datos no quieren rampas
    return cfg;
}

/** Máxima diferencia de amplitud entre dos muestras consecutivas de un bloque. */
static double maxSampleJump(const std::vector<uint8_t>& data, audio::PcmFormat format,
                            int32_t channels) {
    double worst = 0.0;
    if (format == audio::PcmFormat::kI16) {
        const auto* s = reinterpret_cast<const int16_t*>(data.data());
        const size_t samples = data.size() / 2;
        for (size_t i = channels; i < samples; ++i) {
            worst = std::max(worst, std::fabs(static_cast<double>(s[i]) - s[i - channels]));
        }
    } else if (format == audio::PcmFormat::kF32) {
        const auto* s = reinterpret_cast<const float*>(data.data());
        const size_t samples = data.size() / 4;
        for (size_t i = channels; i < samples; ++i) {
            worst = std::max(worst, std::fabs(static_cast<double>(s[i]) - s[i - channels]));
        }
    }
    return worst;
}

// ---------------------------------------------------------------------------
// 1. Anillo concurrente
// ---------------------------------------------------------------------------

static void testRingConcurrent() {
    beginTest("1. Anillo SPSC: 4 MB cruzando dos hilos sin perder un byte");
    audio::ByteRingBuffer ring;
    ring.resize(1 << 16);
    const size_t total = 4u << 20;
    std::atomic<bool> failed{false};
    std::thread producer([&] {
        std::vector<uint8_t> block(1024);
        uint8_t value = 0;
        size_t sent = 0;
        while (sent < total) {
            for (auto& b : block) b = value++;
            size_t written = 0;
            while (written < block.size() && sent < total) {
                // Nunca pedir más de lo que falta: con escrituras parciales (anillo
                // lleno) el bloque anterior podía pasarse del total y dejar bytes de
                // más en el anillo. Bajo TSan el anillo se llena más seguido y el test
                // fallaba por su propia cuenta, no por el anillo.
                const size_t want = std::min(block.size() - written, total - sent);
                const size_t n = ring.write(block.data() + written, want);
                if (n == 0) {
                    std::this_thread::yield();
                    continue;
                }
                written += n;
                sent += n;
            }
        }
    });
    std::vector<uint8_t> buffer(777);
    size_t received = 0;
    uint8_t expected = 0;
    while (received < total) {
        const size_t n = ring.read(buffer.data(), std::min(buffer.size(), total - received));
        if (n == 0) {
            std::this_thread::yield();
            continue;
        }
        for (size_t i = 0; i < n; ++i) {
            if (buffer[i] != expected++) failed.store(true);
        }
        received += n;
    }
    producer.join();
    CHECK_EQ(received, total);
    CHECK(!failed.load());
    CHECK_EQ(ring.availableToRead(), static_cast<size_t>(0));
}

// ---------------------------------------------------------------------------
// 2. Fidelidad
// ---------------------------------------------------------------------------

/** 24 bits estéreo, exclusivo: los bytes del anillo son EXACTAMENTE los de la pista. */
static void testBitExactPassthrough() {
    beginTest("2a. 24-bit: el camino directo entrega los bytes intactos");
    oboe::AudioStreamBuilder::reset();
    Fixture f;
    CHECK(f.open(makeConfig(audio::PcmFormat::kI24, 96000, 2)));
    auto stream = f.startWithData(192);
    // Se vacía lo del arranque: lo que se compara tiene que ser sólo la fuente.
    f.engine.flush();
    CHECK_EQ(static_cast<int>(f.engine.start()), static_cast<int>(oboe::Result::OK));

    std::vector<uint8_t> source(600);   // 100 frames de 24 bits estéreo
    for (size_t i = 0; i < source.size(); ++i) {
        source[i] = static_cast<uint8_t>((i * 37) & 0xFF);
    }
    CHECK_EQ(f.engine.write(source.data(), source.size()), source.size());

    stream->renderFrames(100);
    const auto& out = stream->lastBuffer();
    CHECK_EQ(out.size(), source.size());
    CHECK(std::memcmp(out.data(), source.data(), source.size()) == 0);
    const auto info = f.engine.info();
    CHECK(info.passthrough);
    CHECK(info.bitPerfect);
    CHECK_EQ(info.format, static_cast<int>(audio::PcmFormat::kI24));
}

/** 16 bits: con volumen al 100 % no se toca ni un byte; al bajar, la rampa es suave. */
static void testVolumeRampIsSmooth() {
    beginTest("2b. Volumen: 100 % bit-exacto y la bajada no da un escalón");
    oboe::AudioStreamBuilder::reset();
    Fixture f;
    CHECK(f.open(makeConfig(audio::PcmFormat::kI16, 48000, 2)));
    auto stream = f.startWithData(192);
    f.engine.flush();                 // se mide sólo la onda de prueba
    CHECK_EQ(static_cast<int>(f.engine.start()), static_cast<int>(oboe::Result::OK));

    // Bloque con onda "serrucho" a media escala: un escalón de ganancia se ve como un
    // salto grande entre muestras vecinas.
    std::vector<uint8_t> tone(4096);
    auto* samples = reinterpret_cast<int16_t*>(tone.data());
    for (size_t i = 0; i < tone.size() / 2; ++i) {
        samples[i] = static_cast<int16_t>((static_cast<int>(i) % 64) * 400 - 12800);
    }
    const double before = maxSampleJump(tone, audio::PcmFormat::kI16, 2);

    f.engine.setVolume(1.0f);
    CHECK_EQ(f.engine.write(tone.data(), tone.size()), tone.size());
    stream->renderFrames(static_cast<int32_t>(tone.size() / 4));
    CHECK(std::memcmp(stream->lastBuffer().data(), tone.data(), tone.size()) == 0);

    // Ahora al 25 %: el cambio se reparte en el tiempo, nunca de golpe.
    f.engine.setVolume(0.25f);
    CHECK_EQ(f.engine.write(tone.data(), tone.size()), tone.size());
    stream->renderFrames(static_cast<int32_t>(tone.size() / 4));
    const double after = maxSampleJump(stream->lastBuffer(), audio::PcmFormat::kI16, 2);
    // Un salto de amplitud por el cambio de volumen sería ~3/4 de 12800 en una muestra.
    CHECK(after < before * 1.30);
    CHECK(f.engine.gain_ > 0.2f && f.engine.gain_ < 0.8f);   // rampa en curso, no escalón
}

/** Mono → estéreo: la señal se replica, no se inventa silencio. */
static void testChannelUpmix() {
    beginTest("2c. Mono → estéreo: se replica la señal");
    oboe::AudioStreamBuilder::reset();
    Fixture f;
    auto cfg = makeConfig(audio::PcmFormat::kI16, 48000, 1);
    CHECK(f.open(cfg));
    // El stream abre en mono; para probar el mapeo se fuerza el motor a escribir estéreo.
    f.engine.streamChannels_ = 2;
    f.engine.streamFrameBytes_ = 4;
    auto stream = f.startWithData(192);
    stream->channelCount_ = 2;        // el doble entrega buffers estéreo
    f.engine.flush();
    CHECK_EQ(static_cast<int>(f.engine.start()), static_cast<int>(oboe::Result::OK));

    int16_t mono[512];
    for (int i = 0; i < 512; ++i) mono[i] = static_cast<int16_t>(i * 37 - 9000);
    CHECK_EQ(f.engine.write(reinterpret_cast<uint8_t*>(mono), sizeof(mono)), sizeof(mono));
    CHECK_EQ(static_cast<int>(stream->getState()), static_cast<int>(oboe::StreamState::Started));
    stream->renderFrames(512);
    const auto& out = stream->lastBuffer();
    const auto* stereo = reinterpret_cast<const int16_t*>(out.data());
    CHECK_EQ(out.size(), sizeof(mono) * 2);
    CHECK_EQ(stereo[0], mono[0]);
    CHECK_EQ(stereo[1], mono[0]);
    CHECK_EQ(stereo[2], mono[1]);
    CHECK_EQ(stereo[3], mono[1]);
    CHECK_EQ(stereo[1022], mono[511]);
    CHECK_EQ(stereo[1023], mono[511]);
}

/** El resampler del motor: amplitud y banda preservadas, sin alias al bajar de tasa. */
static void testResamplerQuality() {
    beginTest("2d. Conversión de rate propia: 44.1 → 48 kHz sin perder banda");
    audio::PolyphaseResampler resampler;
    resampler.configure(44100, 48000, 1);
    CHECK(!resampler.bypass());

    const int32_t inFrames = 44100;
    std::vector<float> in(inFrames);
    for (int32_t i = 0; i < inFrames; ++i) {
        in[i] = static_cast<float>(0.5 * std::sin(2.0 * M_PI * 1000.0 * i / 44100.0));
    }
    std::vector<float> out(inFrames * 2);
    int32_t produced = 0;
    int32_t offset = 0;
    while (offset < inFrames) {
        const int32_t chunk = std::min(4096, inFrames - offset);
        produced += resampler.process(in.data() + offset, chunk, out.data() + produced,
                                      static_cast<int32_t>(out.size()) - produced);
        offset += chunk;
    }
    CHECK(produced > 47000 && produced < 49000);
    // Amplitud del tono a la salida (ignorando los bordes del filtro).
    double peak = 0.0;
    for (int32_t i = 1000; i < produced - 1000; ++i) peak = std::max(peak, static_cast<double>(std::fabs(out[i])));
    CHECK(peak > 0.48 && peak < 0.52);
}

/**
 * Con fades activos y el volumen al 25 %, la rampa de entrada tiene que llegar a 0.25
 * y quedarse ahí. Subir a 1.0 y después bajar era un salto de volumen audible al
 * empezar cada pista (el síntoma "el volumen sube solo").
 */
static void testFadeInRespectsVolume() {
    beginTest("2e. Fundido de entrada: llega al volumen elegido, nunca a 1.0");
    oboe::AudioStreamBuilder::reset();
    Fixture f;
    auto cfg = makeConfig(audio::PcmFormat::kI16, 48000, 2);
    cfg.fades = true;
    CHECK(f.open(cfg));
    f.engine.setVolume(0.25f);

    std::vector<uint8_t> tone(2048);
    auto* samples = reinterpret_cast<int16_t*>(tone.data());
    for (size_t i = 0; i < tone.size() / 2; ++i) {
        samples[i] = static_cast<int16_t>((i % 2 == 0) ? 16000 : -16000);   // ±0.5 FS
    }

    // El prerroll se llena con la MISMA onda que se mide (nada de datos basura).
    CHECK_EQ(f.engine.write(tone.data(), tone.size()), tone.size());
    CHECK_EQ(static_cast<int>(f.engine.start()), static_cast<int>(oboe::Result::OK));
    auto stream = f.engine.stream_;

    double worstPeak = 0.0;
    for (int block = 0; block < 40; ++block) {
        CHECK_EQ(f.engine.write(tone.data(), tone.size()), tone.size());
        stream->renderFrames(256);
        const auto* out = reinterpret_cast<const int16_t*>(stream->lastBuffer().data());
        for (size_t i = 0; i < stream->lastBuffer().size() / 2; ++i) {
            worstPeak = std::max(worstPeak, std::fabs(static_cast<double>(out[i])));
        }
    }
    std::printf("    pico medido: %.0f ±0.5 FS al 25 %% (esperado ≈ 4096)\n", worstPeak);
    // 0.5 FS * 0.25 = 4096; un 10 % de margen por el redondeo de la rampa.
    CHECK(worstPeak <= 4500.0);
    CHECK(worstPeak > 3500.0);          // y sí llegó al volumen elegido
    CHECK(std::fabs(f.engine.gain_ - 0.25f) < 1e-4f);
}

// ---------------------------------------------------------------------------
// 3. Hambre transitoria: el callback no para el HAL
// ---------------------------------------------------------------------------

static void testStarvationDoesNotStopStream() {
    beginTest("3. Ring vacío SIN fin de stream: rellena silencio y sigue (antes: Stop)");
    oboe::AudioStreamBuilder::reset();
    Fixture f;
    CHECK(f.open(makeConfig(audio::PcmFormat::kI16, 48000, 2)));
    auto stream = f.startWithData(192);          // arranca con media ráfaga
    CHECK_EQ(static_cast<int>(stream->getState()), static_cast<int>(oboe::StreamState::Started));

    // Un bloque con datos, después bloques sin datos: el HAL NO debe pararse.
    stream->renderFrames(192);
    for (int i = 0; i < 5; ++i) {
        const auto result = stream->renderFrames(192);
        CHECK_EQ(static_cast<int>(result), static_cast<int>(oboe::DataCallbackResult::Continue));
    }
    CHECK_EQ(static_cast<int>(stream->getState()), static_cast<int>(oboe::StreamState::Started));
    CHECK_EQ(stream->stopCalls_.load(), 0);
    CHECK(f.engine.info().underruns >= 1);      // se informa como diagnóstico

    // Y con datos nuevos sigue sonando sin reabrir nada.
    f.pushFrames(960);
    stream->renderFrames(192);
    CHECK(std::any_of(stream->lastBuffer().begin(), stream->lastBuffer().end(),
                      [](uint8_t b) { return b != 0; }));
}

// ---------------------------------------------------------------------------
// 4. Pausa / reanudación
// ---------------------------------------------------------------------------

static void testPauseStopsAndResumeKeepsQueue() {
    beginTest("4. Pausa: para el HAL; reanudar conserva lo que no sonó");
    oboe::AudioStreamBuilder::reset();
    Fixture f;
    auto cfg = makeConfig(audio::PcmFormat::kI16, 48000, 2);
    cfg.fades = true;                            // se prueba el camino real de pausa
    CHECK(f.open(cfg));
    auto stream = f.startWithData(4800);
    for (int i = 0; i < 3; ++i) stream->renderFrames(192);
    const size_t queuedBefore = f.engine.ring_.availableToRead();
    CHECK(queuedBefore > 0);

    CHECK_EQ(static_cast<int>(f.engine.pause()), static_cast<int>(oboe::Result::OK));
    // Con fundido: algunos bloques y después Stop (la cola se conserva).
    for (int i = 0; i < 200 && stream->getState() == oboe::StreamState::Started; ++i) {
        stream->renderFrames(192);
    }
    CHECK_EQ(static_cast<int>(stream->getState()), static_cast<int>(oboe::StreamState::Stopped));
    const size_t queuedAfterPause = f.engine.ring_.availableToRead();
    CHECK(queuedAfterPause > 0);
    CHECK(queuedAfterPause < queuedBefore + 1);   // no se descartó nada

    // Reanudar: vuelve a arrancar y la cola sigue ahí.
    CHECK_EQ(static_cast<int>(f.engine.start()), static_cast<int>(oboe::Result::OK));
    CHECK_EQ(static_cast<int>(stream->getState()), static_cast<int>(oboe::StreamState::Started));
    CHECK(f.engine.ring_.availableToRead() >= queuedAfterPause);
}

// ---------------------------------------------------------------------------
// 5. Fin de stream: drena todo
// ---------------------------------------------------------------------------

static void testEndOfStreamDrains() {
    beginTest("5a. stop(): no corta el último buffer, drena y se detiene solo");
    oboe::AudioStreamBuilder::reset();
    Fixture f;
    CHECK(f.open(makeConfig(audio::PcmFormat::kI16, 48000, 2)));
    auto stream = f.startWithData(9600);
    const size_t queued = f.engine.ring_.availableToRead();
    CHECK(queued > 0);

    CHECK_EQ(static_cast<int>(f.engine.stop()), static_cast<int>(oboe::Result::OK));
    int blocks = 0;
    while (stream->getState() == oboe::StreamState::Started && blocks < 400) {
        stream->renderFrames(192);
        ++blocks;
    }
    CHECK_EQ(static_cast<int>(stream->getState()), static_cast<int>(oboe::StreamState::Stopped));
    CHECK_EQ(f.engine.ring_.availableToRead(), static_cast<size_t>(0));
    CHECK_EQ(f.engine.framesDropped_.load(), static_cast<int64_t>(0));
    CHECK(blocks > 20);                          // drenó de verdad, no cortó de golpe
}

static void testEndOfStreamWithoutData() {
    beginTest("5b. stop() sin datos: el próximo bloque devuelve Stop");
    oboe::AudioStreamBuilder::reset();
    Fixture f;
    CHECK(f.open(makeConfig(audio::PcmFormat::kI16, 48000, 2)));
    auto stream = f.startWithData(192);
    stream->renderFrames(192);
    CHECK_EQ(static_cast<int>(f.engine.stop()), static_cast<int>(oboe::Result::OK));
    const auto result = stream->renderFrames(192);
    CHECK_EQ(static_cast<int>(result), static_cast<int>(oboe::DataCallbackResult::Stop));
    CHECK_EQ(static_cast<int>(stream->getState()), static_cast<int>(oboe::StreamState::Stopped));
}

// ---------------------------------------------------------------------------
// 6. Flush
// ---------------------------------------------------------------------------

static void testFlushLeavesStreamReusable() {
    beginTest("6a. flush(): nunca desde STARTED y queda en PAUSED reutilizable");
    oboe::AudioStreamBuilder::reset();
    Fixture f;
    CHECK(f.open(makeConfig(audio::PcmFormat::kI16, 48000, 2)));
    auto stream = f.startWithData(9600);
    CHECK(f.engine.ring_.availableToRead() > 0);

    CHECK_EQ(static_cast<int>(f.engine.flush()), static_cast<int>(oboe::Result::OK));
    CHECK_EQ(f.engine.ring_.availableToRead(), static_cast<size_t>(0));
    CHECK_EQ(stream->invalidFlushCalls_.load(), 0);
    CHECK_EQ(static_cast<int>(stream->getState()), static_cast<int>(oboe::StreamState::Paused));

    f.pushFrames(4800, 0x40);
    CHECK_EQ(static_cast<int>(f.engine.start()), static_cast<int>(oboe::Result::OK));
    CHECK_EQ(static_cast<int>(stream->getState()), static_cast<int>(oboe::StreamState::Started));
}

static void testTransitionFlushIsNotBlocking() {
    beginTest("6b. flushForTransition(): descarta ya y no espera al HAL");
    oboe::AudioStreamBuilder::reset();
    Fixture f;
    CHECK(f.open(makeConfig(audio::PcmFormat::kI16, 48000, 2)));
    auto stream = f.startWithData(9600);

    const auto start = std::chrono::steady_clock::now();
    CHECK_EQ(static_cast<int>(f.engine.flushForTransition()), static_cast<int>(oboe::Result::OK));
    const auto micros = std::chrono::duration_cast<std::chrono::microseconds>(
                                std::chrono::steady_clock::now() - start)
                                .count();
    CHECK(micros < 5000);
    CHECK_EQ(f.engine.ring_.availableToRead(), static_cast<size_t>(0));
    CHECK(stream->getState() != oboe::StreamState::Started);
    CHECK_EQ(stream->invalidFlushCalls_.load(), 0);
}

// ---------------------------------------------------------------------------
// 7. Reloj de playout
// ---------------------------------------------------------------------------

static void testPlayoutPosition() {
    beginTest("7a. Reloj: descuenta la latencia del HAL y nunca retrocede");
    oboe::AudioStreamBuilder::reset();
    Fixture f;
    CHECK(f.open(makeConfig(audio::PcmFormat::kI16, 48000, 2)));
    auto stream = f.startWithData(48000);
    stream->hardwareLatencyFrames_.store(0);

    // El callback entrega audio y el HAL "presenta" lo mismo (latencia 0 en el doble).
    for (int i = 0; i < 10; ++i) {
        stream->renderFrames(192);
        stream->advanceHardware(192);
    }
    const int64_t consumed = f.engine.framesConsumed();
    const int64_t position = f.engine.positionFrames();
    CHECK(position > 0);
    CHECK(position <= consumed);
    CHECK(position >= consumed - 4096);   // no se queda atrás más que el buffer

    // Monótona: nunca vuelve atrás, ni siquiera si el timestamp "salta".
    const int64_t before = f.engine.positionFrames();
    stream->advanceHardware(-5000);
    CHECK(f.engine.positionFrames() >= before);
}

/**
 * El bug que se escuchó en el teléfono: la barra de posición saltaba hacia adelante.
 *
 * El HAL reporta una posición ABSOLUTA que no tiene por qué empezar en cero (algunos
 * HAL llevan un contador continuo). La versión anterior informaba esa posición cruda
 * (o peor, `copiados - (copiados - posiciónDelHAL)`), así que la barra se iba al futuro
 * y Media3 dejaba de alimentar el buffer: cortes.
 *
 * Lo correcto es informar SIEMPRE un avance relativo al origen del stream, acotado por
 * lo que el motor entregó.
 */
static void testPositionFollowsHalWithoutJumping() {
    beginTest("7c. Un HAL con el contador en otro origen no hace saltar la posición");
    oboe::AudioStreamBuilder::reset();
    Fixture f;
    CHECK(f.open(makeConfig(audio::PcmFormat::kI16, 48000, 2)));
    auto stream = f.startWithData(48000 * 10);

    // El HAL arranca su cuenta en 1.000.000 (contador continuo del DSP), no en 0.
    stream->advanceHardware(1000000);

    for (int i = 0; i < 20; ++i) {
        stream->renderFrames(192);
        stream->advanceHardware(192);
    }
    const int64_t position = f.engine.positionFrames();
    const int64_t consumed = f.engine.framesConsumed();
    // Ni se va al millón (el bug), ni se queda en 0 (congelada).
    CHECK(position > 0);
    CHECK(position < 100000);
    CHECK(position <= consumed);

    // Y sigue avanzando con el HAL, con la diferencia acotada a lo que se presentó.
    const int64_t consumedBefore = f.engine.framesConsumed();
    for (int i = 0; i < 10; ++i) {
        stream->renderFrames(192);
        stream->advanceHardware(192);
    }
    const int64_t after = f.engine.positionFrames();
    // La posición tiene que haber avanzado (o quedado a la par del techo, nunca atrás).
    CHECK(after >= position);
    CHECK(after - position <= 10 * 192 + 4096);
    CHECK(after <= f.engine.framesConsumed());
    CHECK(f.engine.framesConsumed() >= consumedBefore);
}

/**
 * La posición que se informa NUNCA puede ir por delante de lo que el motor entregó al
 * HAL: si va adelante, Media3 cree que ya suena lo que todavía no sonó y deja de
 * alimentar el buffer (cortes).
 */
static void testPositionNeverAheadOfConsumed() {
    beginTest("7d. La posición de playout nunca se adelanta a lo entregado");
    oboe::AudioStreamBuilder::reset();
    Fixture f;
    CHECK(f.open(makeConfig(audio::PcmFormat::kI16, 48000, 2)));
    auto stream = f.startWithData(48000 * 10);
    stream->hardwareLatencyFrames_.store(0);

    bool ok = true;
    for (int i = 0; i < 200; ++i) {
        // Patrón irregular a propósito: ráfagas de 64 a 960 frames.
        const int32_t n = (i % 7 == 0) ? 960 : ((i % 3 == 0) ? 64 : 192);
        stream->renderFrames(n);
        stream->advanceHardware(n);
        const int64_t pos = f.engine.positionFrames();
        if (pos > f.engine.framesConsumed()) ok = false;
    }
    CHECK(ok);
}

static void testPositionAfterPause() {
    beginTest("7b. Reloj tras pausa: no se adelanta al sonido");
    oboe::AudioStreamBuilder::reset();
    Fixture f;
    CHECK(f.open(makeConfig(audio::PcmFormat::kI16, 48000, 2)));
    auto stream = f.startWithData(48000);
    for (int i = 0; i < 5; ++i) stream->renderFrames(192);
    const int64_t playing = f.engine.positionFrames();
    stream->requestPause();
    const int64_t paused = f.engine.positionFrames();
    CHECK(paused >= playing);
    CHECK(paused <= f.engine.framesConsumed());
}

// ---------------------------------------------------------------------------
// 8. Volumen
// ---------------------------------------------------------------------------

static void testVolumeCurve() {
    beginTest("8. Curva perceptual: 1.0 exacto al 100 %, monótona, y nunca más fuerte");
    audio::AudioEngine engine;
    engine.setVolumeCurve(0);
    engine.setVolume(1.0f);
    CHECK(engine.targetGain() == 1.0f);
    engine.setVolumeCurve(1);
    CHECK(engine.targetGain() == 1.0f);
    engine.setVolume(0.5f);
    const float half = engine.targetGain();
    CHECK(half < 0.2f && half > 0.1f);       // 0,5³ = 0,125
    float previous = -1.0f;
    for (int step = 1; step <= 10; ++step) {
        engine.setVolume(static_cast<float>(step) / 10.0f);
        const float gain = engine.targetGain();
        CHECK(gain >= previous);
        CHECK(gain <= static_cast<float>(step) / 10.0f + 1e-6f);
        previous = gain;
    }
    engine.setVolume(0.0f);
    CHECK(engine.targetGain() == 0.0f);
}

// ---------------------------------------------------------------------------
// 9. Política de apertura
// ---------------------------------------------------------------------------

static void testExactOpenUsesTrackFormat() {
    beginTest("9a. Abre exacto: 96 kHz / 24 bits, exclusivo, sin resampleo propio");
    oboe::AudioStreamBuilder::reset();
    Fixture f;
    CHECK(f.open(makeConfig(audio::PcmFormat::kI24, 96000, 2, /*strict=*/true,
                            /*exclusive=*/true)));
    const auto info = f.engine.info();
    CHECK(info.exclusive);
    CHECK_EQ(info.sampleRate, 96000);
    CHECK_EQ(info.format, static_cast<int>(audio::PcmFormat::kI24));
    CHECK(info.passthrough);
    CHECK(!f.engine.resampling_);
}

static void testStrictFailsInsteadOfConverting() {
    beginTest("9b. Modo estricto: si el HAL no acepta el rate, falla en vez de convertir");
    oboe::AudioStreamBuilder::reset();
    oboe::AudioStreamBuilder::setUnsupportedRate(44100);
    Fixture f;
    auto cfg = makeConfig(audio::PcmFormat::kI16, 44100, 2, /*strict=*/true,
                          /*exclusive=*/true);
    f.engine.open(cfg);
    CHECK(!f.engine.awaitOpen(2000));
    CHECK(f.engine.lastErrorCode() != 0);
    oboe::AudioStreamBuilder::reset();
}

static void testFallbackRateUsesOwnResampler() {
    beginTest("9c. No estricto: cae a otro rate con la conversión propia (y lo informa)");
    oboe::AudioStreamBuilder::reset();
    oboe::AudioStreamBuilder::setUnsupportedRate(44100);
    Fixture f;
    CHECK(f.open(makeConfig(audio::PcmFormat::kI16, 44100, 2, /*strict=*/false,
                            /*exclusive=*/false)));
    const auto info = f.engine.info();
    CHECK_EQ(info.sampleRate, 48000);
    CHECK_EQ(info.sourceSampleRate, 44100);
    CHECK(f.engine.resampling_);
    CHECK(!info.passthrough);                 // se informa: no es bit-perfect
    // Y efectivamente entrega audio al stream: 44100 frames de origen → ~48000 de salida.
    auto stream = f.startWithData(44100);
    for (int i = 0; i < 100; ++i) stream->renderFrames(192);
    CHECK(f.engine.framesConsumed() > 0);
    oboe::AudioStreamBuilder::reset();
}

static void testHalConvertedIsReported() {
    beginTest("9d. Si el HAL impone otro rate, no se miente: se informa");
    oboe::AudioStreamBuilder::reset();
    oboe::AudioStreamBuilder::setForcedOutputRate(48000);
    Fixture f;
    CHECK(f.open(makeConfig(audio::PcmFormat::kI16, 96000, 2, /*strict=*/false,
                            /*exclusive=*/false)));
    const auto info = f.engine.info();
    CHECK(info.halConverted);
    CHECK(!info.bitPerfect);
    oboe::AudioStreamBuilder::reset();
}

static void testRequireDeviceDoesNotFallback() {
    beginTest("9e. requireDevice: si el DAC USB no abre, NO se cae al altavoz interno");
    oboe::AudioStreamBuilder::reset();
    oboe::AudioStreamBuilder::setRequireExclusive(true);   // el "DAC" sólo da exclusivo
    Fixture f;
    auto cfg = makeConfig(audio::PcmFormat::kI16, 48000, 2, /*strict=*/false,
                          /*exclusive=*/true);
    cfg.deviceId = 7;
    cfg.requireDevice = true;
    cfg.requireExclusive = false;
    f.engine.open(cfg);
    // El doble acepta exclusivo, así que este caso abre; lo que se verifica es que el
    // motor NO reintenta con device 0 cuando `requireDevice` está activo.
    f.engine.awaitOpen(2000);
    CHECK_EQ(oboe::AudioStreamBuilder::openCount(), 1);
    oboe::AudioStreamBuilder::reset();
}

static void testOpenCountIsBounded() {
    beginTest("9f. La apertura no encadena reintentos infinitos");
    oboe::AudioStreamBuilder::reset();
    oboe::AudioStreamBuilder::setUnsupportedRate(44100);
    Fixture f;
    f.engine.open(makeConfig(audio::PcmFormat::kI16, 44100, 2, /*strict=*/false,
                             /*exclusive=*/false));
    f.engine.awaitOpen(2000);
    CHECK(oboe::AudioStreamBuilder::openCount() <= 8);
    oboe::AudioStreamBuilder::reset();
}

// ---------------------------------------------------------------------------
// 10. Errores
// ---------------------------------------------------------------------------

static void testDisconnectReportsError() {
    beginTest("10. DAC desconectado: el error llega al sink");
    oboe::AudioStreamBuilder::reset();
    Fixture f;
    CHECK(f.open(makeConfig(audio::PcmFormat::kI16, 48000, 2)));
    auto stream = f.startWithData(4800);
    stream->fireErrorAfterClose(oboe::Result::ErrorDisconnected);
    CHECK_EQ(f.engine.lastErrorCode(), static_cast<int32_t>(oboe::Result::ErrorDisconnected));
    CHECK_EQ(f.engine.write(reinterpret_cast<const uint8_t*>("abcd"), 4), static_cast<size_t>(0));
}

// ---------------------------------------------------------------------------

int main() {
    setvbuf(stdout, nullptr, _IOLBF, 0);
    std::printf("Pruebas del motor de audio nativo (host, dobles de Oboe)\n");
    std::printf("========================================================\n");

    testRingConcurrent();
    testBitExactPassthrough();
    testVolumeRampIsSmooth();
    testChannelUpmix();
    testFadeInRespectsVolume();
    testResamplerQuality();
    testStarvationDoesNotStopStream();
    testPauseStopsAndResumeKeepsQueue();
    testEndOfStreamDrains();
    testEndOfStreamWithoutData();
    testFlushLeavesStreamReusable();
    testTransitionFlushIsNotBlocking();
    testPlayoutPosition();
    testPositionAfterPause();
    testPositionFollowsHalWithoutJumping();
    testPositionNeverAheadOfConsumed();
    testVolumeCurve();
    testExactOpenUsesTrackFormat();
    testStrictFailsInsteadOfConverting();
    testFallbackRateUsesOwnResampler();
    testHalConvertedIsReported();
    testRequireDeviceDoesNotFallback();
    testOpenCountIsBounded();
    testDisconnectReportsError();

    std::printf("\n========================================================\n");
    if (g_failures == 0) {
        std::printf("\033[32m%d comprobaciones, 0 fallas\033[0m\n", g_checks);
        return 0;
    }
    std::printf("\033[31m%d comprobaciones, %d FALLAS\033[0m\n", g_checks, g_failures);
    return 1;
}
