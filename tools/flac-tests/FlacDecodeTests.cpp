// Arnés del decodificador FLAC propio (app/src/main/cpp/audio/FlacDecoder.cpp).
//
// Verifica lo único que importa para bit-perfect: que la muestra entera que entrega el
// decodificador sea **exactamente** la del archivo.
//
// Los archivos de prueba (`fixtures/*.flac`) se generan con libsndfile/python (soundfile)
// y el arnés compara cada frame decodificado contra el `.raw` correspondiente, que es el
// mismo archivo leído por una implementación independiente (libFLAC vía libsndfile):
//
//   fixtures/test_<bits>_<rate>_<channels>.flac   archivo comprimido
//   fixtures/test_<bits>_<rate>_<channels>.raw    PCM intercalado little-endian
//                                                 (16 bits: int16, 24 bits: 3 bytes)
//
// Comprueba además: metadatos (rate/canales/profundidad/duración), que un seek a mitad de
// pista devuelva las mismas muestras que la lectura secuencial hasta ese punto, y que el
// archivo se lea completo sin "frames" fantasma al final.
//
// Compilar y ejecutar: tools/flac-tests/run.sh
#include <algorithm>
#include <cstdio>
#include <memory>
#include <cstring>
#include <string>
#include <vector>

#include "audio/FlacDecoder.h"

static int g_failures = 0;
static int g_checks = 0;

#define CHECK(cond)                                                                  \
    do {                                                                             \
        ++g_checks;                                                                   \
        if (!(cond)) {                                                                \
            std::printf("    \033[31mFALLA\033[0m %s:%d  %s\n", __FILE__, __LINE__,   \
                        #cond);                                                       \
            ++g_failures;                                                             \
        }                                                                             \
    } while (0)

#define CHECK_EQ(actual, expected)                                                    \
    do {                                                                              \
        ++g_checks;                                                                    \
        const auto a_ = (actual);                                                      \
        const auto e_ = (expected);                                                    \
        if (!(a_ == e_)) {                                                             \
            std::printf("    \033[31mFALLA\033[0m %s:%d  %s == %s (obtenido %lld, "   \
                        "esperado %lld)\n",                                            \
                        __FILE__, __LINE__, #actual, #expected,                        \
                        static_cast<long long>(a_), static_cast<long long>(e_));        \
            ++g_failures;                                                              \
        }                                                                              \
    } while (0)

static void beginTest(const char* name) {
    std::printf("\n\033[1m%s\033[0m\n", name);
}

static std::vector<uint8_t> readAll(const std::string& path) {
    std::vector<uint8_t> data;
    std::FILE* file = std::fopen(path.c_str(), "rb");
    if (file == nullptr) return data;
    std::fseek(file, 0, SEEK_END);
    const long size = std::ftell(file);
    std::fseek(file, 0, SEEK_SET);
    if (size > 0) {
        data.resize(static_cast<size_t>(size));
        const size_t got = std::fread(data.data(), 1, data.size(), file);
        data.resize(got);
    }
    std::fclose(file);
    return data;
}

struct Case {
    int bits;
    int rate;
    int channels;
};

/**
 * Un caso completo: decodificar todo el archivo y comparar byte a byte con el PCM de
 * referencia, más metadatos, conteo de frames y coherencia del seek.
 */
static void runCase(const std::string& directory, const Case& testCase) {
    char name[256];
    std::snprintf(name, sizeof(name), "%d bits · %d Hz · %d canal(es)", testCase.bits,
                  testCase.rate, testCase.channels);
    beginTest(name);

    char base[512];
    std::snprintf(base, sizeof(base), "%s/test_%d_%d_%d", directory.c_str(), testCase.bits,
                  testCase.rate, testCase.channels);
    const std::string flacPath = std::string(base) + ".flac";
    const std::string rawPath = std::string(base) + ".raw";

    const auto expected = readAll(rawPath);
    if (expected.empty()) {
        std::printf("    \033[33mSALTA\033[0m no existe %s (ejecutá tools/flac-tests/run.sh)\n",
                    rawPath.c_str());
        return;
    }

    audio::FlacDecoder decoder;
    std::string error;
    const bool opened = decoder.open(flacPath, &error);
    CHECK(opened);
    if (!opened) {
        std::printf("    error: %s\n", error.c_str());
        return;
    }

    const auto& info = decoder.info();
    CHECK_EQ(info.sampleRate, testCase.rate);
    CHECK_EQ(info.channels, testCase.channels);
    CHECK_EQ(info.bitsPerSample, testCase.bits);
    CHECK(info.totalSamples > 0);
    CHECK(info.maxBlockSize >= 16);
    const size_t frameBytes =
            static_cast<size_t>(audio::FlacDecoder::bytesPerSample(info.bitsPerSample)) *
            static_cast<size_t>(info.channels);
    const size_t expectedFrames = expected.size() / frameBytes;
    CHECK_EQ(static_cast<size_t>(info.totalSamples), expectedFrames);
    // La duración que declara STREAMINFO debe coincidir con los samples reales.
    CHECK_EQ(info.durationMs, static_cast<int64_t>(expectedFrames) * 1000 / testCase.rate);

    // --- lectura completa ------------------------------------------------------
    std::vector<uint8_t> decoded(expected.size() + frameBytes, 0);
    size_t totalFrames = 0;
    int32_t chunkFrames = 4096;
    for (;;) {
        uint8_t* destination = decoded.data() + totalFrames * frameBytes;
        const size_t room = decoded.size() - totalFrames * frameBytes;
        const int32_t got = decoder.read(destination, chunkFrames, room);
        if (got <= 0) break;
        totalFrames += static_cast<size_t>(got);
        if (totalFrames > expectedFrames + 8) break;   // protección contra bucles
    }
    CHECK_EQ(totalFrames, expectedFrames);
    CHECK_EQ(static_cast<size_t>(decoder.framesDecoded()), expectedFrames);

    size_t mismatches = 0;
    size_t firstMismatch = 0;
    for (size_t i = 0; i < std::min(decoded.size(), expected.size()); ++i) {
        if (decoded[i] != expected[i]) {
            if (mismatches == 0) firstMismatch = i;
            ++mismatches;
        }
    }
    CHECK_EQ(mismatches, static_cast<size_t>(0));
    if (mismatches != 0) {
        const size_t frame = firstMismatch / frameBytes;
        const size_t channel = (firstMismatch / (frameBytes / testCase.channels)) % testCase.channels;
        std::printf("    primer byte distinto: frame %zu, canal %zu (byte %zu de %zu)\n",
                    frame, channel, firstMismatch, expected.size());
    }
    std::printf("    %zu frames, %zu bytes comparados, %zu diferencias\n", totalFrames,
                expected.size(), mismatches);

    // --- seek --------------------------------------------------------------
    const int64_t target = static_cast<int64_t>(expectedFrames / 3);
    audio::FlacDecoder seeker;
    CHECK(seeker.open(flacPath, &error));
    CHECK(seeker.seek(target));
    std::vector<uint8_t> afterSeek(64 * frameBytes, 0);
    const int32_t got = seeker.read(afterSeek.data(), 64, afterSeek.size());
    CHECK_EQ(got, 64);
    // Las 64 muestras tras el seek deben ser las mismas que en la lectura secuencial.
    const size_t offset = static_cast<size_t>(target) * frameBytes;
    const bool sameAsSequential =
            std::memcmp(afterSeek.data(), expected.data() + offset, 64 * frameBytes) == 0;
    CHECK(sameAsSequential);
    if (!sameAsSequential) {
        // Si falla, ayuda ver si el desvío es de un frame entero (off-by-one en el seek).
        std::printf("    seek a %lld no coincide con la lectura secuencial\n",
                    static_cast<long long>(target));
    }

    // --- más allá del final ------------------------------------------------
    std::vector<uint8_t> tail(frameBytes * 4, 0);
    const int32_t past = seeker.read(tail.data(), 4, tail.size());
    CHECK(past >= 0);

    // --- reapertura en medio del archivo ------------------------------------
    // Es lo que hace el extractor de Media3 cuando el DataSource se reabre tras un seek:
    // el extractor guarda el offset del primer frame y llama a openFrom() con el STREAMINFO
    // ya leído. El resultado tiene que ser idéntico a la lectura secuencial.
    {
        audio::FlacDecoder reopened;
        std::string reopenError;
        const bool reopenOk = reopened.openFrom(
                std::make_unique<audio::FileByteSource>(flacPath), decoder.info(),
                decoder.info().audioOffset, /* firstSampleNumber= */ 0, &reopenError);
        CHECK(reopenOk);
        if (reopenOk) {
            std::vector<uint8_t> first(frameBytes * 32, 0);
            const int32_t firstGot = reopened.read(first.data(), 32, first.size());
            CHECK_EQ(firstGot, 32);
            CHECK(std::memcmp(first.data(), expected.data(), 32 * frameBytes) == 0);
        }
    }

    // --- índice de frames ---------------------------------------------------
    // El extractor de Media3 necesita saber el byte de cada frame para buscar en archivos
    // sin SEEKTABLE. El índice se arma escaneando cabeceras sin decodificar: tiene que
    // coincidir exactamente con los frames que aparecen al decodificar de verdad.
    {
        audio::FileByteSource source(flacPath);
        std::vector<audio::FlacFrameIndexEntry> index;
        std::string indexError;
        const bool built = audio::FlacDecoder::buildFrameIndex(source, decoder.info(), &index,
                                                               &indexError);
        CHECK(built);
        CHECK(!index.empty());
        if (built && !index.empty()) {
            CHECK_EQ(index.front().byteOffset, decoder.info().audioOffset);
            CHECK_EQ(index.front().sampleNumber, static_cast<int64_t>(0));
            int64_t covered = 0;
            for (const auto& entry : index) covered += entry.frameSamples;
            CHECK_EQ(covered, static_cast<int64_t>(expectedFrames));
            // El último sample del índice tiene que ser el total del archivo.
            CHECK_EQ(index.back().sampleNumber + index.back().frameSamples,
                     static_cast<int64_t>(expectedFrames));

            // Cada entrada del índice se usa para reabrir el stream ahí: el decodificador
            // tiene que producir exactamente el mismo PCM que en la lectura secuencial.
            size_t verified = 0;
            for (size_t i = 0; i < index.size(); ++i) {
                audio::FlacDecoder atFrame;
                std::string frameError;
                const bool openedHere = atFrame.openFrom(
                        std::make_unique<audio::FileByteSource>(flacPath), decoder.info(),
                        index[i].byteOffset, index[i].sampleNumber, &frameError);
                CHECK(openedHere);
                if (!openedHere) break;
                std::vector<uint8_t> chunk(frameBytes * static_cast<size_t>(index[i].frameSamples), 0);
                const int32_t gotChunk = atFrame.read(chunk.data(), index[i].frameSamples, chunk.size());
                CHECK_EQ(gotChunk, index[i].frameSamples);
                const size_t byteOffset = static_cast<size_t>(index[i].sampleNumber) * frameBytes;
                const size_t bytes = static_cast<size_t>(index[i].frameSamples) * frameBytes;
                const bool same = byteOffset + bytes <= expected.size() &&
                                  std::memcmp(chunk.data(), expected.data() + byteOffset, bytes) == 0;
                CHECK(same);
                if (!same) {
                    std::printf("    reapertura en el frame %zu (byte %lld, sample %lld) no coincide\n",
                                i, static_cast<long long>(index[i].byteOffset),
                                static_cast<long long>(index[i].sampleNumber));
                    break;
                }
                ++verified;
            }
            CHECK_EQ(verified, index.size());
        }
    }

    // --- corrupción ---------------------------------------------------------
    // Se daña un byte del medio del stream: el CRC-16 del frame tiene que detectarlo. En
    // modo estricto el decodificador corta ahí (es lo que corresponde cuando el archivo
    // está mal); en modo tolerante sigue, pero cuenta el error.
    {
        auto bytes = readAll(flacPath);
        CHECK(!bytes.empty());
        if (!bytes.empty()) {
            const size_t damage = bytes.size() / 2;
            bytes[damage] ^= 0x5A;
            const std::string damagedPath = std::string(base) + ".damaged.flac";
            std::FILE* out = std::fopen(damagedPath.c_str(), "wb");
            CHECK(out != nullptr);
            if (out != nullptr) {
                std::fwrite(bytes.data(), 1, bytes.size(), out);
                std::fclose(out);

                audio::FlacDecoder strict;
                strict.setStrictCrc(true);
                std::string damagedError;
                const bool damagedOpened = strict.open(damagedPath, &damagedError);
                CHECK(damagedOpened);
                if (damagedOpened) {
                    std::vector<uint8_t> sink(64 * frameBytes, 0);
                    int64_t produced = 0;
                    for (int i = 0; i < 4096; ++i) {
                        const int32_t gotChunk = strict.read(sink.data(), 64, sink.size());
                        if (gotChunk <= 0) break;
                        produced += gotChunk;
                    }
                    // Tiene que fallar antes del final: el daño no puede pasar inadvertido.
                    CHECK(produced < static_cast<int64_t>(expectedFrames));
                }

                // En modo tolerante no se corta, pero el error tiene que quedar contado (o,
                // si el byte dañado rompió la estructura del frame antes del CRC, el stream
                // también termina antes).
                audio::FlacDecoder tolerant;
                if (tolerant.open(damagedPath, &damagedError)) {
                    std::vector<uint8_t> sink(64 * frameBytes, 0);
                    int64_t produced = 0;
                    for (int i = 0; i < 4096; ++i) {
                        const int32_t gotChunk = tolerant.read(sink.data(), 64, sink.size());
                        if (gotChunk <= 0) break;
                        produced += gotChunk;
                    }
                    const bool detected = tolerant.crcErrors() > 0 ||
                                          produced < static_cast<int64_t>(expectedFrames);
                    CHECK(detected);
                    if (!detected) {
                        std::printf("    el byte dañado no fue detectado (%d errores de CRC)\n",
                                    tolerant.crcErrors());
                    }
                }
                std::remove(damagedPath.c_str());
            }
        }
    }
}

int main(int argc, char** argv) {
    setvbuf(stdout, nullptr, _IOLBF, 0);
    const std::string directory = argc > 1 ? argv[1] : "fixtures";
    std::printf("Decodificador FLAC propio: comparación bit a bit contra libFLAC\n");
    std::printf("=============================================================\n");

    const Case cases[] = {
        {16, 44100, 1}, {16, 44100, 2}, {16, 48000, 2}, {16, 96000, 2},
        {24, 44100, 2}, {24, 48000, 1}, {24, 48000, 2}, {24, 96000, 2},
    };
    for (const auto& testCase : cases) runCase(directory, testCase);

    std::printf("\n=============================================================\n");
    if (g_failures == 0) {
        std::printf("\033[32m%d comprobaciones, 0 fallas\033[0m\n", g_checks);
        return 0;
    }
    std::printf("\033[31m%d comprobaciones, %d FALLAS\033[0m\n", g_checks, g_failures);
    return 1;
}
