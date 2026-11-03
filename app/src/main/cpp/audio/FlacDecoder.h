// Decodificador FLAC propio (sin dependencias externas).
//
// # Por qué
//
// El camino bit-perfect de la app depende de que la muestra entera llegue intacta al DAC.
// Hoy el FLAC lo decodifica MediaCodec / FFmpeg a través de Media3, y para 24 bits eso
// significa pasar por **float**: `c2.android.flac.decoder` sólo emite 16 bits o float, y
// `ToFloatPcmAudioProcessor` multiplica por 1/0x7FFFFFFF. Un FLAC de 24 bits deja de ser
// bit-perfect antes de llegar al motor. Mornye resuelve esto decodificando FLAC en C++ con
// libFLAC y alimentando su motor directamente; acá se hace lo mismo **sin libFLAC** (sin
// añadir una dependencia LGPL al proyecto): sólo la especificación RFC 9639.
//
// # Qué implementa
//
//  - Metadatos: STREAMINFO, SEEKTABLE y salto del resto (VORBIS_COMMENT, PICTURE, PADDING).
//  - Frames: cabecera con CRC-8 verificado, subframes CONSTANT / VERBATIM / FIXED 0-4 /
//    LPC 1-32 (coeficientes y shift), bits desperdiciados, Rice particionado (método 0 y 1,
//    con escape a residual sin codificar), decorrelación estéreo LR/LS/RS/MS y CRC-16 de
//    frame verificado.
//  - Salida: PCM intercalado **con signo y en el ancho nativo** del archivo
//    (16 → int16, 24 → 3 bytes LE, 32 → int32), que es exactamente lo que el motor sabe
//    escribir al DAC sin conversión.
//
// # Contrato
//
// `open()` lee los metadatos y deja el lector al inicio del primer frame. `read()` devuelve
// frames intercalados; 0 significa fin del stream. `seek()` usa la SEEKTABLE cuando existe
// (y si no, decodifica y descarta). Para reabrir un stream en medio del archivo (lo que hace
// el extractor de Media3 después de un seek) está `openFrom()`, que recibe el STREAMINFO ya
// leído y el offset de un frame.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <string>
#include <vector>

namespace audio {

class ByteSource {
public:
    virtual ~ByteSource() = default;
    /** Lee hasta `count` bytes; devuelve los leídos (0 = fin del archivo). */
    virtual size_t read(uint8_t* out, size_t count) = 0;
    /** Posición absoluta del lector. */
    virtual int64_t position() const = 0;
    /** Salta a una posición absoluta. */
    virtual bool seek(int64_t offset) = 0;
    /** Tamaño total en bytes, o -1 si se desconoce. */
    virtual int64_t size() const { return -1; }
};

/** Fuente sobre un archivo del sistema (arnés de pruebas y utilidades de escritorio). */
class FileByteSource : public ByteSource {
public:
    explicit FileByteSource(const std::string& path);
    ~FileByteSource() override;
    bool valid() const { return file_ != nullptr; }
    size_t read(uint8_t* out, size_t count) override;
    int64_t position() const override { return position_; }
    bool seek(int64_t offset) override;
    int64_t size() const override { return size_; }

private:
    std::FILE* file_ = nullptr;
    int64_t position_ = 0;
    int64_t size_ = -1;
};

/** Lector con buffer propio y CRC en marcha: amortiza las lecturas en bloques de 64 KB. */
class BufferedReader {
public:
    explicit BufferedReader(ByteSource& source, size_t capacity = 64 * 1024);

    uint8_t peekByte();
    uint8_t readByte();
    /** Lee un byte sin alimentarlo al CRC (para leer el CRC-8 que cierra la cabecera). */
    uint8_t readRawByte();
    /** Alimenta al CRC un byte leído por fuera (el CRC-8 de cabecera). */
    void feedCrc(uint8_t byte);
    uint32_t readBits(int count);                 // MSB-first, hasta 32 bits
    int64_t readSigned(int count);                // entero con signo de `count` bits
    /** Cuenta ceros hasta el próximo 1 (incluye el 1). `limit` corta streams corruptos. */
    uint32_t readUnary(uint32_t limit = 1u << 24);
    void skipBytes(int64_t count);
    bool alignToByte();                           // consume los bits de relleno
    uint16_t readCrc16();                         // lee 2 bytes alineados

    /** CRC de todo byte consumido desde `startCrc()`: CRC-16 de frame y CRC-8 de cabecera. */
    void startCrc();
    uint16_t crcNow() const { return crc16_; }
    uint8_t headerCrc8() const { return crc8_; }
    void suspendCrc() { crcActive_ = false; }
    void resumeCrc() { crcActive_ = true; }

    bool eofReached() const { return eofReached_; }
    int64_t position() const;

private:
    bool fill(size_t needed);
    uint8_t pullByte();       // única puerta de salida de bytes del buffer
    bool hasBuffered() const { return position_ < available_; }

    ByteSource& source_;
    std::vector<uint8_t> buffer_;
    size_t position_ = 0;
    size_t available_ = 0;
    int64_t positionBase_ = 0;
    uint32_t bitBuffer_ = 0;
    int bitCount_ = 0;
    bool eofReached_ = false;
    uint16_t crc16_ = 0;
    uint8_t crc8_ = 0;
    bool crcActive_ = false;
};

struct FlacStreamInfo {
    int32_t minBlockSize = 0;
    int32_t maxBlockSize = 0;
    int32_t minFrameSize = 0;
    int32_t maxFrameSize = 0;
    int32_t sampleRate = 0;
    int32_t channels = 0;
    int32_t bitsPerSample = 0;
    int64_t totalSamples = 0;      // 0 = desconocido
    int64_t audioOffset = 0;       // byte del primer frame
    int64_t durationMs = 0;
    bool hasSeekTable = false;
    /** STREAMINFO crudo (34 bytes), para poder reabrir el stream en medio del archivo. */
    std::array<uint8_t, 34> raw{};
};

struct FlacSeekPoint {
    int64_t sampleNumber = 0;
    int64_t streamOffset = 0;
    int32_t frameSamples = 0;
};

/** Entrada del índice de frames: byte de inicio, primer sample y tamaño del bloque. */
struct FlacFrameIndexEntry {
    int64_t byteOffset = 0;
    int64_t sampleNumber = 0;
    int32_t frameSamples = 0;
};

/**
 * Decodificador FLAC sin dependencias externas.
 *
 * No es re-entrante entre hilos: un decodificador por hilo. No reserva memoria en el camino
 * de decodificación (los buffers se dimensionan en `open()`).
 */
class FlacDecoder {
public:
    FlacDecoder();
    ~FlacDecoder();

    FlacDecoder(const FlacDecoder&) = delete;
    FlacDecoder& operator=(const FlacDecoder&) = delete;

    /** Lee los metadatos y deja el lector en el primer frame. */
    bool open(std::unique_ptr<ByteSource> source, std::string* error = nullptr);
    bool open(const std::string& path, std::string* error = nullptr);

    /**
     * Reabre un stream en `frameOffset` reutilizando el STREAMINFO ya conocido (lo usa el
     * extractor de Media3 cuando el DataSource se reabre después de un seek). No lee
     * metadatos: el offset **tiene** que ser el inicio de un frame.
     */
    bool openFrom(std::unique_ptr<ByteSource> source, const FlacStreamInfo& info,
                  int64_t frameOffset, int64_t firstSampleNumber = 0,
                  std::string* error = nullptr);

    /**
     * Recorre el archivo **sin decodificar** (sólo cabeceras de frame, validadas con su
     * CRC-8 y encadenadas por el número de frame/sample) y arma el índice de frames.
     *
     * Sirve para los FLAC que no traen SEEKTABLE: sin esto, buscar en esos archivos
     * obligaría a decodificar desde el principio. El índice es exacto (los números de
     * sample se acumulan a partir de los tamaños de bloque de cada cabecera) y la
     * decodificación secuencial lo confirma byte a byte en el arnés.
     */
    static bool buildFrameIndex(ByteSource& source, const FlacStreamInfo& info,
                                std::vector<FlacFrameIndexEntry>* out,
                                std::string* error = nullptr);

    const FlacStreamInfo& info() const { return info_; }
    const std::vector<FlacSeekPoint>& seekTable() const { return seekTable_; }

    /**
     * Decodifica hasta `maxFrames` frames intercalados en `out` (formato nativo de la
     * profundidad). Devuelve los frames escritos; 0 = fin del stream.
     * `outCapacityBytes` debe ser al menos maxFrames * channels * bytesPerSample.
     */
    int32_t read(uint8_t* out, int32_t maxFrames, size_t outCapacityBytes);

    /** Offset del frame que se está decodificando (o del próximo si aún no empezó). */
    int64_t frameStartOffset() const { return frameStartOffset_; }
    /**
     * Byte donde empieza el próximo frame: es la posición del lector después de decodificar.
     * El extractor la compara con la posición de su `DataSource` para saber si el reproductor
     * reabrió el stream en otro punto.
     */
    int64_t nextFrameOffset() const { return reader_ ? reader_->position() : 0; }
    /** Frames que trae el frame actual (0 si no hay ninguno cargado). */
    int32_t currentFrameSamples() const { return blockSize_; }
    /** Primer sample del frame actual. */
    int64_t currentFrameSample() const { return frameFirstSample_; }

    /**
     * Salta a `sampleNumber`. Devuelve false si no pudo (por ejemplo, sin acceso aleatorio
     * a la fuente y con el objetivo hacia atrás: el extractor pide reabrir el stream).
     */
    bool seek(int64_t sampleNumber);

    int64_t framesDecoded() const { return framesDecoded_; }
    int32_t crcErrors() const { return crcErrors_; }
    /** Cuando es false, un CRC-16 que no cierra se anota y se sigue (robustez en vivo). */
    void setStrictCrc(bool strict) { strictCrc_ = strict; }

    static int32_t bytesPerSample(int32_t bitsPerSample) {
        return bitsPerSample <= 16 ? 2 : (bitsPerSample <= 24 ? 3 : 4);
    }

private:
    bool readMetadata(std::string* error);
    bool prepareBuffers(std::string* error);
    bool decodeFrame();
    bool decodeSubframe(int channel, int32_t* out, int blockSize, int bitsPerSample);
    bool decodeResidual(int32_t* out, int blockSize, int order);

    std::unique_ptr<ByteSource> source_;
    std::unique_ptr<BufferedReader> reader_;
    FlacStreamInfo info_;
    std::vector<FlacSeekPoint> seekTable_;

    // Estado del frame actual.
    int32_t blockSize_ = 0;
    int64_t frameFirstSample_ = 0;
    int64_t frameStartOffset_ = 0;
    int32_t frameNumber_ = 0;
    int32_t channelCount_ = 0;
    int32_t bps_ = 0;
    int32_t sampleRate_ = 0;
    int channelAssignment_ = 0;
    int32_t frameCursor_ = 0;
    int32_t frameSamplesLeft_ = 0;
    bool done_ = false;

    // Buffers (se dimensionan en prepareBuffers()).
    std::vector<std::vector<int32_t>> channels_;
    std::vector<int32_t> residual_;

    int64_t framesDecoded_ = 0;
    int32_t crcErrors_ = 0;
    bool strictCrc_ = false;
};

}  // namespace audio
