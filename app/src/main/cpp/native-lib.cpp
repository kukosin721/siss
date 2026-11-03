#include <jni.h>

#include <new>

#include <android/log.h>

#include "audio/AudioEngine.h"

#define LOG_TAG "KukosinAudioJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Puente JNI del motor de audio. Protocolo v2.
//
// Formato PCM:  0 = I16 · 1 = I24 (3 bytes) · 2 = Float32 · 3 = I32.
// Estado:       valores de oboe::StreamState.
//
// Info[i] (20 slots):
//   0 api (0 AAudio / 1 OpenSL)   1 formato stream
//   2 rate stream                 3 canales stream
//   4 framesPerBurst              5 ringFrames (colchón del motor)
//   6 deviceId                    7 xRuns (-1 = no reportado)
//   8 exclusivo                   9 bitPerfect
//  10 passthrough                11 errorCode
//  12 volumen %                  13 formato origen
//  14 rate origen                15 canales origen
//  16 latencia real (µs)         17 MMAP
//  18 underruns                  19 el HAL impuso conversión
//
// Ningún entero se "adivina": todo lo que la UI muestra viene de lo que el hardware
// devolvió, que es la única forma de que "bit-perfect" signifique algo.
// ---------------------------------------------------------------------------

namespace {

audio::AudioEngine* engineFrom(jlong handle) {
    return reinterpret_cast<audio::AudioEngine*>(handle);
}

audio::PcmFormat toFormat(jint code) {
    switch (code) {
        case 0: return audio::PcmFormat::kI16;
        case 1: return audio::PcmFormat::kI24;
        case 2: return audio::PcmFormat::kF32;
        case 3: return audio::PcmFormat::kI32;
        default: return audio::PcmFormat::kI16;
    }
}

constexpr jint kInfoSize = 20;

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_kukosin_app_audio_AudioEngine_nativeCreate(JNIEnv* /*env*/, jobject /*thiz*/) {
    auto* engine = new (std::nothrow) audio::AudioEngine();
    if (engine == nullptr) {
        LOGE("sin memoria para AudioEngine");
        return 0;
    }
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_kukosin_app_audio_AudioEngine_nativeDelete(JNIEnv* /*env*/, jobject /*thiz*/,
                                                    jlong handle) {
    delete engineFrom(handle);
}

JNIEXPORT jint JNICALL
Java_com_kukosin_app_audio_AudioEngine_nativeConfigure(JNIEnv* /*env*/, jobject /*thiz*/,
                                                       jlong handle, jint format,
                                                       jint sampleRate, jint channelCount,
                                                       jint deviceId, jboolean strict,
                                                       jboolean requireExclusive,
                                                       jboolean requireDevice, jint bufferMs,
                                                       jboolean fades, jint prerollMs,
                                                       jint volumeCurve) {
    auto* engine = engineFrom(handle);
    if (engine == nullptr) return static_cast<jint>(oboe::Result::ErrorNull);
    audio::EngineConfig config;
    config.sourceFormat = toFormat(format);
    config.sourceSampleRate = sampleRate > 0 ? sampleRate : 0;
    config.sourceChannels = channelCount > 0 ? channelCount : 2;
    config.deviceId = deviceId;
    config.strictBitPerfect = strict == JNI_TRUE;
    config.requireExclusive = requireExclusive == JNI_TRUE;
    config.requireDevice = requireDevice == JNI_TRUE;
    config.bufferMs = bufferMs > 0 ? bufferMs : 250;
    config.fades = fades == JNI_TRUE;
    config.prerollMs = prerollMs;
    config.volumeCurve = volumeCurve;
    return static_cast<jint>(engine->open(config));
}

/** Resultado de la apertura diferida: OK si ya está abierto. */
JNIEXPORT jint JNICALL
Java_com_kukosin_app_audio_AudioEngine_nativeAwaitOpen(JNIEnv* /*env*/, jobject /*thiz*/,
                                                       jlong handle, jint timeoutMs) {
    auto* engine = engineFrom(handle);
    if (engine == nullptr) return static_cast<jint>(oboe::Result::ErrorNull);
    if (!engine->awaitOpen(timeoutMs > 0 ? timeoutMs : 2000)) {
        const int32_t code = engine->lastErrorCode();
        return code != 0 ? code : static_cast<jint>(oboe::Result::ErrorUnavailable);
    }
    return static_cast<jint>(oboe::Result::OK);
}

JNIEXPORT jint JNICALL
Java_com_kukosin_app_audio_AudioEngine_nativeStart(JNIEnv* /*env*/, jobject /*thiz*/,
                                                   jlong handle) {
    auto* engine = engineFrom(handle);
    return engine == nullptr ? static_cast<jint>(oboe::Result::ErrorNull)
                             : static_cast<jint>(engine->start());
}

JNIEXPORT jint JNICALL
Java_com_kukosin_app_audio_AudioEngine_nativePause(JNIEnv* /*env*/, jobject /*thiz*/,
                                                   jlong handle) {
    auto* engine = engineFrom(handle);
    return engine == nullptr ? static_cast<jint>(oboe::Result::ErrorNull)
                             : static_cast<jint>(engine->pause());
}

JNIEXPORT jint JNICALL
Java_com_kukosin_app_audio_AudioEngine_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/,
                                                  jlong handle) {
    auto* engine = engineFrom(handle);
    return engine == nullptr ? static_cast<jint>(oboe::Result::ErrorNull)
                             : static_cast<jint>(engine->stop());
}

/**
 * Fin de stream bloqueante: Media3 llama a `AudioOutput.stop()` y espera que lo
 * pendiente suene. El motor drena y el callback se detiene solo; acá se espera, como
 * máximo `timeoutMs`, a que la posición de playout alcance lo encolado.
 */
JNIEXPORT jint JNICALL
Java_com_kukosin_app_audio_AudioEngine_nativeStopAndWait(JNIEnv* /*env*/, jobject /*thiz*/,
                                                         jlong handle, jint timeoutMs) {
    auto* engine = engineFrom(handle);
    if (engine == nullptr) return static_cast<jint>(oboe::Result::ErrorNull);
    const oboe::Result result = engine->stop();
    if (result != oboe::Result::OK) return static_cast<jint>(result);
    engine->drain(timeoutMs > 0 ? timeoutMs : 750);
    return static_cast<jint>(oboe::Result::OK);
}

/**
 * Descarta lo pendiente y deja el stream reutilizable (PAUSED).
 *
 * `waitForHardware = false` es el camino de las transiciones entre pistas: descarta los
 * datos propios de inmediato y no espera las transiciones de estado del HAL (que entre
 * canción y canción se escuchaban como bache).
 */
JNIEXPORT jint JNICALL
Java_com_kukosin_app_audio_AudioEngine_nativeFlush(JNIEnv* /*env*/, jobject /*thiz*/,
                                                   jlong handle, jboolean waitForHardware) {
    auto* engine = engineFrom(handle);
    if (engine == nullptr) return static_cast<jint>(oboe::Result::ErrorNull);
    return static_cast<jint>(waitForHardware == JNI_TRUE ? engine->flush()
                                                        : engine->flushForTransition());
}

JNIEXPORT void JNICALL
Java_com_kukosin_app_audio_AudioEngine_nativeSetVolume(JNIEnv* /*env*/, jobject /*thiz*/,
                                                       jlong handle, jfloat volume) {
    auto* engine = engineFrom(handle);
    if (engine != nullptr) engine->setVolume(volume);
}

JNIEXPORT void JNICALL
Java_com_kukosin_app_audio_AudioEngine_nativeSetVolumeCurve(JNIEnv* /*env*/, jobject /*thiz*/,
                                                            jlong handle, jint curve) {
    auto* engine = engineFrom(handle);
    if (engine != nullptr) engine->setVolumeCurve(curve);
}

JNIEXPORT void JNICALL
Java_com_kukosin_app_audio_AudioEngine_nativeSetFades(JNIEnv* /*env*/, jobject /*thiz*/,
                                                      jlong handle, jboolean enabled) {
    auto* engine = engineFrom(handle);
    if (engine != nullptr) engine->setFadesEnabled(enabled == JNI_TRUE);
}

/**
 * Copia PCM ya decodificado al anillo del motor.
 * Devuelve los bytes de ORIGEN aceptados (0 = reintentar más tarde).
 */
JNIEXPORT jint JNICALL
Java_com_kukosin_app_audio_AudioEngine_nativeWrite(JNIEnv* env, jobject /*thiz*/,
                                                   jlong handle, jobject buffer,
                                                   jint offset, jint length) {
    auto* engine = engineFrom(handle);
    if (engine == nullptr || buffer == nullptr || length <= 0 || offset < 0) return 0;
    void* base = env->GetDirectBufferAddress(buffer);
    if (base == nullptr) return 0;
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (capacity < 0 || static_cast<jlong>(offset) + static_cast<jlong>(length) > capacity) {
        return 0;
    }
    const size_t accepted = engine->write(
            static_cast<const uint8_t*>(base) + offset, static_cast<size_t>(length));
    return static_cast<jint>(accepted);
}

JNIEXPORT jint JNICALL
Java_com_kukosin_app_audio_AudioEngine_nativeErrorCode(JNIEnv* /*env*/, jobject /*thiz*/,
                                                       jlong handle) {
    auto* engine = engineFrom(handle);
    if (engine == nullptr) return static_cast<jint>(oboe::Result::ErrorNull);
    return static_cast<jint>(engine->lastErrorCode());
}

JNIEXPORT jint JNICALL
Java_com_kukosin_app_audio_AudioEngine_nativeGetState(JNIEnv* /*env*/, jobject /*thiz*/,
                                                      jlong handle) {
    auto* engine = engineFrom(handle);
    if (engine == nullptr) return static_cast<jint>(oboe::StreamState::Closed);
    return static_cast<jint>(engine->state());
}

/** Posición de PLAYOUT en microsegundos (lo que ya sonó, no lo copiado). */
JNIEXPORT jlong JNICALL
Java_com_kukosin_app_audio_AudioEngine_nativeGetPositionMicros(JNIEnv* /*env*/, jobject /*thiz*/,
                                                              jlong handle) {
    auto* engine = engineFrom(handle);
    return engine == nullptr ? 0 : static_cast<jlong>(engine->positionMicros());
}

JNIEXPORT jlong JNICALL
Java_com_kukosin_app_audio_AudioEngine_nativeGetLatencyMicros(JNIEnv* /*env*/, jobject /*thiz*/,
                                                             jlong handle) {
    auto* engine = engineFrom(handle);
    return engine == nullptr ? -1 : static_cast<jlong>(engine->latencyMicros());
}

JNIEXPORT jboolean JNICALL
Java_com_kukosin_app_audio_AudioEngine_nativeIsEndOfStream(JNIEnv* /*env*/, jobject /*thiz*/,
                                                           jlong handle) {
    auto* engine = engineFrom(handle);
    return engine != nullptr && engine->endOfStreamReached() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_kukosin_app_audio_AudioEngine_nativeGetSourceFrames(JNIEnv* /*env*/, jobject /*thiz*/,
                                                             jlong handle) {
    auto* engine = engineFrom(handle);
    return engine == nullptr ? 0 : static_cast<jlong>(engine->sourceFramesQueued());
}

JNIEXPORT void JNICALL
Java_com_kukosin_app_audio_AudioEngine_nativeGetInfo(JNIEnv* env, jobject /*thiz*/,
                                                     jlong handle, jintArray out) {
    if (out == nullptr) return;
    const jsize size = env->GetArrayLength(out);
    if (size < kInfoSize) return;

    jint values[kInfoSize] = {-1, -1, 0, 0, 0, 0, 0, -1, 0, 0,
                              0,  0,  100, -1, 0, 0, -1, 0, 0, 0};
    auto* engine = engineFrom(handle);
    if (engine != nullptr) {
        const audio::StreamInfo info = engine->info();
        values[0] = info.api;
        values[1] = info.format;
        values[2] = info.sampleRate;
        values[3] = info.channels;
        values[4] = info.framesPerBurst;
        // Slot 5 = ANILLO del motor: es el colchón con el que Media3 dimensiona su
        // alimentación. Si se le da el buffer del HAL (una ráfaga), alimenta de menos y
        // el anillo se queda vacío: cortes.
        values[5] = info.ringFrames > 0 ? info.ringFrames : info.bufferFrames;
        values[6] = info.deviceId;
        values[7] = info.xRuns;
        values[8] = info.exclusive ? 1 : 0;
        values[9] = info.bitPerfect ? 1 : 0;
        values[10] = info.passthrough ? 1 : 0;
        values[11] = info.errorCode;
        values[12] = info.volumePercent;
        values[13] = info.sourceFormat;
        values[14] = info.sourceSampleRate;
        values[15] = info.sourceChannels;
        values[16] = info.latencyMicros;
        values[17] = info.mmap ? 1 : 0;
        values[18] = info.underruns;
        values[19] = info.halConverted ? 1 : 0;
    }
    env->SetIntArrayRegion(out, 0, kInfoSize, values);
}

} // extern "C"
