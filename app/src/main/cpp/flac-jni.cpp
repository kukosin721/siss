#include <jni.h>

#include <android/log.h>

#include <unistd.h>

#include <algorithm>
#include <memory>
#include <new>
#include <string>
#include <vector>

#include "audio/FlacDecoder.h"

// Puente JNI del decodificador FLAC propio (com.kukosin.app.playback.FlacDecoderBridge).
//
// No hay callbacks hacia Java: el extractor pide buffers y recibe frames, igual que
// cualquier otro extractor de Media3. El trabajo pesado (decodificar) ocurre en el hilo de
// carga de Media3, fuera del hilo de audio.

#define LOG_TAG "KukosinFlacJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/** Fuente sobre un descriptor de archivo ya abierto por Java (`FileInputStream.fd`). */
class FdByteSource : public audio::ByteSource {
public:
    explicit FdByteSource(int fd) : fd_(fd) {}

    // `pread` en vez de `read`: el offset lo lleva esta instancia y no el descriptor. Así
    // el decodificador y el indexador de frames pueden usar el mismo `FileDescriptor` a la
    // vez sin pisarse (con `read`/`lseek` compartirían el offset del archivo y uno
    // corrompería al otro en la próxima recarga del buffer).
    size_t read(uint8_t* out, size_t count) override {
        if (fd_ < 0 || count == 0) return 0;
        size_t total = 0;
        while (total < count) {
            const ssize_t got = ::pread(fd_, out + total, count - total,
                                        static_cast<off_t>(position_ + static_cast<int64_t>(total)));
            if (got <= 0) break;
            total += static_cast<size_t>(got);
        }
        position_ += static_cast<int64_t>(total);
        return total;
    }

    int64_t position() const override { return position_; }

    bool seek(int64_t offset) override {
        if (fd_ < 0 || offset < 0) return false;
        position_ = offset;
        return true;
    }

    int64_t size() const override { return size_; }

    void setSize(int64_t size) { size_ = size; }

private:
    int fd_;
    int64_t position_ = 0;
    int64_t size_ = -1;
};

struct FlacHandle {
    audio::FlacDecoder decoder;
    std::unique_ptr<FdByteSource> source;
};

FlacHandle* handleFrom(jlong handle) {
    return reinterpret_cast<FlacHandle*>(handle);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_kukosin_app_playback_FlacDecoderBridge_nativeOpenFile(JNIEnv* env, jobject /*thiz*/,
                                                               jstring path) {
    if (path == nullptr) return 0;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return 0;
    std::string filePath(chars);
    env->ReleaseStringUTFChars(path, chars);

    auto* handle = new (std::nothrow) FlacHandle();
    if (handle == nullptr) return 0;
    std::string error;
    if (!handle->decoder.open(filePath, &error)) {
        LOGE("no se pudo abrir %s: %s", filePath.c_str(), error.c_str());
        delete handle;
        return 0;
    }
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT jlong JNICALL
Java_com_kukosin_app_playback_FlacDecoderBridge_nativeOpenFd(JNIEnv* /*env*/, jobject /*thiz*/,
                                                             jint fd, jlong length) {
    if (fd < 0) return 0;
    auto* handle = new (std::nothrow) FlacHandle();
    if (handle == nullptr) return 0;
    handle->source = std::make_unique<FdByteSource>(fd);
    handle->source->setSize(length);
    std::string error;
    if (!handle->decoder.open(std::move(handle->source), &error)) {
        LOGE("no se pudo abrir el fd %d: %s", fd, error.c_str());
        delete handle;
        return 0;
    }
    return reinterpret_cast<jlong>(handle);
}

/** Reabre el stream en `frameOffset` con el STREAMINFO que ya tenía el extractor. */
JNIEXPORT jlong JNICALL
Java_com_kukosin_app_playback_FlacDecoderBridge_nativeOpenAtFrame(
        JNIEnv* env, jobject /*thiz*/, jint fd, jlong length, jintArray streamInfo, jint count,
        jlong frameOffset, jlong firstSampleNumber) {
    if (fd < 0 || streamInfo == nullptr || count != 34) return 0;
    jint values[34];
    env->GetIntArrayRegion(streamInfo, 0, 34, values);
    uint8_t raw[34];
    for (int i = 0; i < 34; ++i) raw[i] = static_cast<uint8_t>(values[i] & 0xFF);

    auto* handle = new (std::nothrow) FlacHandle();
    if (handle == nullptr) return 0;
    auto source = std::make_unique<FdByteSource>(fd);
    source->setSize(length);

    // Se reconstruye el STREAMINFO desde los 34 bytes crudos: es la misma información que
    // tenía el decodificador que ya leyó los metadatos.
    audio::FlacStreamInfo info;
    const uint8_t* block = raw;
    info.minBlockSize = (block[0] << 8) | block[1];
    info.maxBlockSize = (block[2] << 8) | block[3];
    info.minFrameSize = (block[4] << 16) | (block[5] << 8) | block[6];
    info.maxFrameSize = (block[7] << 16) | (block[8] << 8) | block[9];
    info.sampleRate = (block[10] << 12) | (block[11] << 4) | (block[12] >> 4);
    info.channels = ((block[12] >> 1) & 0x07) + 1;
    info.bitsPerSample = (((block[12] & 0x01) << 4) | (block[13] >> 4)) + 1;
    const uint64_t samples = (static_cast<uint64_t>(block[13] & 0x0F) << 32) |
                             (static_cast<uint64_t>(block[14]) << 24) |
                             (static_cast<uint64_t>(block[15]) << 16) |
                             (static_cast<uint64_t>(block[16]) << 8) |
                             static_cast<uint64_t>(block[17]);
    info.totalSamples = static_cast<int64_t>(samples);
    for (int i = 0; i < 34; ++i) info.raw[i] = raw[i];
    info.durationMs = info.totalSamples > 0 && info.sampleRate > 0
            ? info.totalSamples * 1000 / info.sampleRate
            : 0;

    std::string error;
    if (!handle->decoder.openFrom(std::move(source), info, frameOffset, firstSampleNumber, &error)) {
        LOGE("no se pudo reabrir en el frame %lld: %s", static_cast<long long>(frameOffset),
             error.c_str());
        delete handle;
        return 0;
    }
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL
Java_com_kukosin_app_playback_FlacDecoderBridge_nativeClose(JNIEnv* /*env*/, jobject /*thiz*/,
                                                            jlong handle) {
    delete handleFrom(handle);
}

/** Metadatos del stream en un IntArray: 0 rate, 1 canales, 2 bits, 3 totalSamples (alto),
 *  4 totalSamples (bajo), 5 audioOffset (alto), 6 audioOffset (bajo), 7 bytesPerSample,
 *  8 puntos de la SEEKTABLE, 9 errores de CRC acumulados. */
JNIEXPORT jboolean JNICALL
Java_com_kukosin_app_playback_FlacDecoderBridge_nativeGetInfo(JNIEnv* env, jobject /*thiz*/,
                                                              jlong handle, jintArray out) {
    auto* node = handleFrom(handle);
    if (node == nullptr || out == nullptr) return JNI_FALSE;
    if (env->GetArrayLength(out) < 10) return JNI_FALSE;
    const auto& info = node->decoder.info();
    jint values[10];
    values[0] = info.sampleRate;
    values[1] = info.channels;
    values[2] = info.bitsPerSample;
    values[3] = static_cast<jint>(info.totalSamples >> 32);
    values[4] = static_cast<jint>(info.totalSamples & 0xFFFFFFFFLL);
    values[5] = static_cast<jint>(info.audioOffset >> 32);
    values[6] = static_cast<jint>(info.audioOffset & 0xFFFFFFFFLL);
    values[7] = audio::FlacDecoder::bytesPerSample(info.bitsPerSample);
    values[8] = static_cast<jint>(node->decoder.seekTable().size());
    values[9] = node->decoder.crcErrors();
    env->SetIntArrayRegion(out, 0, 10, values);
    return JNI_TRUE;
}

/** Copia los 34 bytes de STREAMINFO como IntArray (para reabrir el stream tras un seek). */
JNIEXPORT jboolean JNICALL
Java_com_kukosin_app_playback_FlacDecoderBridge_nativeGetStreamInfo(JNIEnv* env, jobject /*thiz*/,
                                                                    jlong handle, jintArray out) {
    auto* node = handleFrom(handle);
    if (node == nullptr || out == nullptr) return JNI_FALSE;
    if (env->GetArrayLength(out) < 34) return JNI_FALSE;
    jint values[34];
    const auto& raw = node->decoder.info().raw;
    for (int i = 0; i < 34; ++i) values[i] = raw[i];
    env->SetIntArrayRegion(out, 0, 34, values);
    return JNI_TRUE;
}

/** Puntos de la SEEKTABLE: dos long[] (sample, offset) + un int[] (frameSamples). */
JNIEXPORT jint JNICALL
Java_com_kukosin_app_playback_FlacDecoderBridge_nativeGetSeekTable(JNIEnv* env, jobject /*thiz*/,
                                                                   jlong handle, jlongArray samples,
                                                                   jlongArray offsets,
                                                                   jintArray frameSamples) {
    auto* node = handleFrom(handle);
    if (node == nullptr) return -1;
    const auto& table = node->decoder.seekTable();
    const jsize capacity = samples != nullptr ? env->GetArrayLength(samples) : 0;
    const jsize count = static_cast<jsize>(std::min<size_t>(table.size(), capacity));
    if (count <= 0) return 0;
    std::vector<jlong> sampleValues(static_cast<size_t>(count));
    std::vector<jlong> offsetValues(static_cast<size_t>(count));
    std::vector<jint> frameValues(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        sampleValues[static_cast<size_t>(i)] = static_cast<jlong>(table[static_cast<size_t>(i)].sampleNumber);
        offsetValues[static_cast<size_t>(i)] = static_cast<jlong>(table[static_cast<size_t>(i)].streamOffset);
        frameValues[static_cast<size_t>(i)] = table[static_cast<size_t>(i)].frameSamples;
    }
    env->SetLongArrayRegion(samples, 0, count, sampleValues.data());
    if (offsets != nullptr) env->SetLongArrayRegion(offsets, 0, count, offsetValues.data());
    if (frameSamples != nullptr) env->SetIntArrayRegion(frameSamples, 0, count, frameValues.data());
    return count;
}

/**
 * Decodifica hasta `maxFrames` frames en `out` (ByteBuffer directo o heap con dirección
 * obtenible). Devuelve un long empaquetado: `frames << 32 | bytesDelBloque`.
 * `frames == 0` significa fin del stream (o error de decodificación: se corta la pista en
 * vez de mandar basura al DAC). `bytesDelBloque` es lo que ocupa el bloque en el archivo y
 * es lo que el extractor necesita para mantener su posición sincronizada con el DataSource.
 */
JNIEXPORT jlong JNICALL
Java_com_kukosin_app_playback_FlacDecoderBridge_nativeRead(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jbyteArray out, jint capacity,
        jint maxFrames) {
    auto* node = handleFrom(handle);
    if (node == nullptr || out == nullptr) return 0;
    const jsize available = env->GetArrayLength(out);
    const size_t limit = static_cast<size_t>(std::min<jint>(capacity, available));
    if (limit == 0) return 0;
    // Array de bytes (heap): es lo que Media3 consume por `ParsableByteArray`. Se copia
    // directo al array de Java, sin buffer intermedio.
    auto* base = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(out, nullptr));
    if (base == nullptr) return 0;
    const int32_t got = node->decoder.read(base, maxFrames, limit);
    const int64_t delta = got > 0
            ? node->decoder.nextFrameOffset() - node->decoder.frameStartOffset()
            : 0;
    env->ReleasePrimitiveArrayCritical(out, base, 0);
    return (static_cast<jlong>(got) << 32) | (static_cast<jlong>(delta) & 0xFFFFFFFFLL);
}

JNIEXPORT jboolean JNICALL
Java_com_kukosin_app_playback_FlacDecoderBridge_nativeSeek(JNIEnv* /*env*/, jobject /*thiz*/,
                                                           jlong handle, jlong sample) {
    auto* node = handleFrom(handle);
    if (node == nullptr) return JNI_FALSE;
    return node->decoder.seek(sample) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
