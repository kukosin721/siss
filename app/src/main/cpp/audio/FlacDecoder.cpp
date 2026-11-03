#include "FlacDecoder.h"

#include <algorithm>
#include <cstring>

namespace audio {
namespace {

constexpr int kMaxBlockSize = 65535;
constexpr int kMaxChannels = 8;
constexpr int kMaxLpcOrder = 32;
constexpr int kMaxRicePartitionOrder = 15;

const int32_t kSampleRateTable[12] = {
    -1, 88200, 176400, 192000, 8000, 16000, 22050, 24000, 32000, 44100, 48000, 96000,
};

// CRC-8 de cabecera de frame: polinomio x^8 + x^2 + x + 1 (0x07), init 0.
struct Crc8Table {
    uint8_t value[256];
    Crc8Table() {
        for (int i = 0; i < 256; ++i) {
            uint32_t crc = static_cast<uint32_t>(i);
            for (int bit = 0; bit < 8; ++bit) {
                crc = (crc & 0x80u) ? ((crc << 1) ^ 0x07u) : (crc << 1);
                crc &= 0xFFu;
            }
            value[i] = static_cast<uint8_t>(crc);
        }
    }
};

// CRC-16 de frame FLAC: polinomio x^16 + x^15 + x^2 + 1 (0x8005), init 0, sin reflexión.
struct Crc16Table {
    uint16_t value[256];
    Crc16Table() {
        for (int i = 0; i < 256; ++i) {
            uint32_t crc = static_cast<uint32_t>(i) << 8;
            for (int bit = 0; bit < 8; ++bit) {
                crc = (crc & 0x8000u) ? ((crc << 1) ^ 0x8005u) : (crc << 1);
                crc &= 0xFFFFu;
            }
            value[i] = static_cast<uint16_t>(crc);
        }
    }
};

const Crc8Table kCrc8{};
const Crc16Table kCrc16{};

inline uint8_t crc8Update(uint8_t crc, uint8_t byte) {
    return kCrc8.value[(crc ^ byte) & 0xFF];
}

inline uint16_t crc16Update(uint16_t crc, uint8_t byte) {
    return static_cast<uint16_t>((crc << 8) ^ kCrc16.value[((crc >> 8) ^ byte) & 0xFF]);
}

}  // namespace

// ---------------------------------------------------------------- FileByteSource

FileByteSource::FileByteSource(const std::string& path) {
    file_ = std::fopen(path.c_str(), "rb");
    if (file_ == nullptr) return;
#if defined(_WIN32)
    if (_fseeki64(file_, 0, SEEK_END) == 0) {
        size_ = static_cast<int64_t>(_ftelli64(file_));
    }
#else
    if (fseeko(file_, 0, SEEK_END) == 0) {
        size_ = static_cast<int64_t>(ftello(file_));
    }
#endif
    std::fseek(file_, 0, SEEK_SET);
    position_ = 0;
}

FileByteSource::~FileByteSource() {
    if (file_ != nullptr) std::fclose(file_);
}

size_t FileByteSource::read(uint8_t* out, size_t count) {
    if (file_ == nullptr || count == 0) return 0;
    const size_t got = std::fread(out, 1, count, file_);
    position_ += static_cast<int64_t>(got);
    return got;
}

bool FileByteSource::seek(int64_t offset) {
    if (file_ == nullptr || offset < 0) return false;
#if defined(_WIN32)
    const bool ok = _fseeki64(file_, offset, SEEK_SET) == 0;
#else
    const bool ok = fseeko(file_, static_cast<off_t>(offset), SEEK_SET) == 0;
#endif
    if (ok) position_ = offset;
    return ok;
}

// ---------------------------------------------------------------- BufferedReader

BufferedReader::BufferedReader(ByteSource& source, size_t capacity)
        : source_(source), buffer_(capacity == 0 ? 1 : capacity) {
    // `position()` tiene que ser absoluta aunque la fuente ya venga desplazada (el
    // decodificador crea un lector nuevo después de un seek): se arranca desde la posición
    // real de la fuente, no desde cero.
    positionBase_ = source.position();
}

bool BufferedReader::fill(size_t needed) {
    if (available_ - position_ >= needed) return true;
    if (position_ > 0) {
        const size_t remaining = available_ - position_;
        if (remaining > 0) {
            std::memmove(buffer_.data(), buffer_.data() + position_, remaining);
        }
        positionBase_ += static_cast<int64_t>(position_);
        available_ = remaining;
        position_ = 0;
    }
    while (available_ - position_ < needed && available_ < buffer_.size()) {
        const size_t got = source_.read(buffer_.data() + available_, buffer_.size() - available_);
        if (got == 0) {
            eofReached_ = true;
            break;
        }
        available_ += got;
    }
    return available_ - position_ >= needed;
}

// Única salida de bytes: acá se alimenta el CRC, así ningún camino puede olvidarlo.
uint8_t BufferedReader::pullByte() {
    if (!fill(1)) {
        eofReached_ = true;
        return 0;
    }
    const uint8_t byte = buffer_[position_++];
    if (crcActive_) {
        crc16_ = crc16Update(crc16_, byte);
        crc8_ = crc8Update(crc8_, byte);
    }
    return byte;
}

uint8_t BufferedReader::peekByte() {
    if (!fill(1)) {
        eofReached_ = true;
        return 0;
    }
    return buffer_[position_];
}

uint8_t BufferedReader::readByte() {
    return pullByte();
}

uint8_t BufferedReader::readRawByte() {
    const bool wasActive = crcActive_;
    crcActive_ = false;
    const uint8_t byte = pullByte();
    crcActive_ = wasActive;
    return byte;
}

void BufferedReader::feedCrc(uint8_t byte) {
    crc16_ = crc16Update(crc16_, byte);
}

uint32_t BufferedReader::readBits(int count) {
    uint32_t result = 0;
    while (count > 0) {
        if (bitCount_ == 0) {
            bitBuffer_ = pullByte();
            bitCount_ = 8;
        }
        const int take = std::min(count, bitCount_);
        const uint32_t mask = (take >= 32) ? 0xFFFFFFFFu : ((1u << take) - 1u);
        result = (result << take) | ((bitBuffer_ >> (bitCount_ - take)) & mask);
        bitCount_ -= take;
        count -= take;
    }
    return result;
}

int64_t BufferedReader::readSigned(int count) {
    if (count <= 0) return 0;
    const int bits = std::min(count, 32);
    const uint32_t raw = readBits(bits);
    const uint32_t signBit = 1u << (bits - 1);
    const int32_t value = static_cast<int32_t>(raw ^ signBit) - static_cast<int32_t>(signBit);
    return static_cast<int64_t>(value);
}

uint32_t BufferedReader::readUnary(uint32_t limit) {
    // Devuelve la cantidad de ceros leídos; consume el 1 que cierra el código.
    uint32_t zeros = 0;
    for (;;) {
        if (bitCount_ == 0) {
            bitBuffer_ = pullByte();
            bitCount_ = 8;
        }
        const uint32_t bits = bitBuffer_ & ((1u << bitCount_) - 1u);
        if (bits == 0) {
            zeros += static_cast<uint32_t>(bitCount_);
            bitCount_ = 0;
            if (zeros > limit) return zeros;   // stream corrupto: se corta la cuenta
            continue;
        }
        int highest = 0;
        for (int i = bitCount_ - 1; i >= 0; --i) {
            if ((bits >> i) & 1u) {
                highest = i;
                break;
            }
        }
        zeros += static_cast<uint32_t>(bitCount_ - 1 - highest);
        bitCount_ = highest;   // se consume el 1 incluido
        return zeros;
    }
}

void BufferedReader::skipBytes(int64_t count) {
    if (count <= 0) return;
    alignToByte();
    if (crcActive_) {
        // Con CRC en marcha no se puede saltar por encima del buffer: los bytes cuentan.
        for (int64_t i = 0; i < count; ++i) pullByte();
        return;
    }
    const size_t buffered = available_ - position_;
    if (count <= static_cast<int64_t>(buffered)) {
        position_ += static_cast<size_t>(count);
        return;
    }
    positionBase_ += static_cast<int64_t>(position_) + count;
    position_ = 0;
    available_ = 0;
    source_.seek(positionBase_);
}

bool BufferedReader::alignToByte() {
    // Los bits de relleno ya fueron contados en el CRC al leer su byte: sólo hay que
    // descartar lo que queda del buffer de bits.
    const bool hadBits = bitCount_ != 0;
    bitCount_ = 0;
    bitBuffer_ = 0;
    return hadBits;
}

uint16_t BufferedReader::readCrc16() {
    alignToByte();
    const uint8_t high = readRawByte();
    const uint8_t low = readRawByte();
    return static_cast<uint16_t>((high << 8) | low);
}

int64_t BufferedReader::position() const {
    return positionBase_ + static_cast<int64_t>(position_);
}

void BufferedReader::startCrc() {
    crc16_ = 0;
    crc8_ = 0;
    crcActive_ = true;
}

// ---------------------------------------------------------------- FlacDecoder

FlacDecoder::FlacDecoder() = default;
FlacDecoder::~FlacDecoder() = default;

bool FlacDecoder::open(const std::string& path, std::string* error) {
    auto source = std::make_unique<FileByteSource>(path);
    if (!source->valid()) {
        if (error != nullptr) *error = "no se pudo abrir el archivo";
        return false;
    }
    return open(std::move(source), error);
}

bool FlacDecoder::open(std::unique_ptr<ByteSource> source, std::string* error) {
    source_ = std::move(source);
    reader_ = std::make_unique<BufferedReader>(*source_);
    info_ = FlacStreamInfo();
    seekTable_.clear();
    framesDecoded_ = 0;
    blockSize_ = 0;
    frameFirstSample_ = 0;
    frameStartOffset_ = 0;
    frameCursor_ = 0;
    frameSamplesLeft_ = 0;
    done_ = false;
    crcErrors_ = 0;

    // Algunos archivos traen una etiqueta ID3v2 delante del FLAC: se salta. El orden
    // importa: se leen 4 bytes y sólo si son "ID3" se leen los 6 restantes de la
    // cabecera (leer 10 de entrada y releer 4 dejaba el lector 6 bytes adelantado).
    uint8_t head[4];
    for (int i = 0; i < 4; ++i) head[i] = reader_->readByte();
    if (std::memcmp(head, "ID3", 3) == 0) {
        uint8_t rest[6];
        for (int i = 0; i < 6; ++i) rest[i] = reader_->readByte();
        // Tamaño sincsafe (7 bits por byte) de la etiqueta.
        const int32_t size = ((rest[2] & 0x7F) << 21) | ((rest[3] & 0x7F) << 14) |
                             ((rest[4] & 0x7F) << 7) | (rest[5] & 0x7F);
        reader_->skipBytes(size);
        for (int i = 0; i < 4; ++i) head[i] = reader_->readByte();
    }
    if (std::memcmp(head, "fLaC", 4) != 0) {
        if (error != nullptr) *error = "no es un archivo FLAC (falta la firma fLaC)";
        return false;
    }
    if (!readMetadata(error)) return false;
    return prepareBuffers(error);
}

bool FlacDecoder::openFrom(std::unique_ptr<ByteSource> source, const FlacStreamInfo& info,
                           int64_t frameOffset, int64_t firstSampleNumber, std::string* error) {
    if (frameOffset < 0 || !source) {
        if (error != nullptr) *error = "offset de frame inválido";
        return false;
    }
    source_ = std::move(source);
    info_ = info;
    seekTable_.clear();
    framesDecoded_ = 0;
    blockSize_ = 0;
    frameFirstSample_ = 0;
    frameStartOffset_ = frameOffset;
    frameCursor_ = 0;
    frameSamplesLeft_ = 0;
    done_ = false;
    crcErrors_ = 0;
    if (!prepareBuffers(error)) return false;
    framesDecoded_ = firstSampleNumber;
    frameFirstSample_ = firstSampleNumber;
    if (!source_->seek(frameOffset)) {
        if (error != nullptr) *error = "no se pudo saltar al frame";
        return false;
    }
    reader_ = std::make_unique<BufferedReader>(*source_);
    return true;
}

bool FlacDecoder::prepareBuffers(std::string* error) {
    if (info_.channels < 1 || info_.channels > kMaxChannels ||
        info_.maxBlockSize < 16 || info_.maxBlockSize > kMaxBlockSize ||
        info_.bitsPerSample < 4 || info_.bitsPerSample > 32 || info_.sampleRate <= 0) {
        if (error != nullptr) *error = "STREAMINFO con parámetros no soportados";
        return false;
    }
    channels_.assign(static_cast<size_t>(info_.channels), {});
    for (auto& channel : channels_) {
        channel.assign(static_cast<size_t>(info_.maxBlockSize) + 8, 0);
    }
    residual_.assign(static_cast<size_t>(info_.maxBlockSize) + 8, 0);
    return true;
}

bool FlacDecoder::readMetadata(std::string* error) {
    bool last = false;
    bool haveStreamInfo = false;
    while (!last) {
        const uint8_t header = reader_->readByte();
        last = (header & 0x80) != 0;
        const int type = header & 0x7F;
        const int32_t length = static_cast<int32_t>(reader_->readBits(24));
        if (reader_->eofReached() || length < 0) {
            if (error != nullptr) *error = "bloque de metadatos inválido";
            return false;
        }
        if (type == 0) {   // STREAMINFO
            if (length != 34) {
                if (error != nullptr) *error = "STREAMINFO de tamaño incorrecto";
                return false;
            }
            uint8_t* block = info_.raw.data();
            for (int i = 0; i < 34; ++i) block[i] = reader_->readByte();
            info_.minBlockSize = (block[0] << 8) | block[1];
            info_.maxBlockSize = (block[2] << 8) | block[3];
            info_.minFrameSize = (block[4] << 16) | (block[5] << 8) | block[6];
            info_.maxFrameSize = (block[7] << 16) | (block[8] << 8) | block[9];
            info_.sampleRate = (block[10] << 12) | (block[11] << 4) | (block[12] >> 4);
            info_.channels = ((block[12] >> 1) & 0x07) + 1;
            info_.bitsPerSample = (((block[12] & 0x01) << 4) | (block[13] >> 4)) + 1;
            const uint64_t samples = (static_cast<uint64_t>(block[13] & 0x0F) << 32) |
                                     (static_cast<uint64_t>(block[14]) << 24) |
                                     (static_cast<uint64_t>(block[15]) << 16) |
                                     (static_cast<uint64_t>(block[16]) << 8) |
                                     static_cast<uint64_t>(block[17]);
            info_.totalSamples = static_cast<int64_t>(samples);
            if (info_.sampleRate <= 0 || info_.channels < 1 || info_.channels > kMaxChannels ||
                info_.bitsPerSample < 4 || info_.bitsPerSample > 32 ||
                info_.maxBlockSize < 16 || info_.maxBlockSize > kMaxBlockSize ||
                info_.minBlockSize > info_.maxBlockSize) {
                if (error != nullptr) *error = "STREAMINFO con parámetros no soportados";
                return false;
            }
            info_.durationMs = info_.totalSamples > 0
                    ? info_.totalSamples * 1000 / info_.sampleRate
                    : 0;
            haveStreamInfo = true;
        } else if (type == 3) {   // SEEKTABLE
            const int32_t points = length / 18;
            for (int32_t i = 0; i < points; ++i) {
                FlacSeekPoint point;
                uint64_t sample = 0;
                for (int b = 0; b < 8; ++b) sample = (sample << 8) | reader_->readByte();
                uint64_t offset = 0;
                for (int b = 0; b < 8; ++b) offset = (offset << 8) | reader_->readByte();
                point.frameSamples = (reader_->readByte() << 8) | reader_->readByte();
                if (sample == 0xFFFFFFFFFFFFFFFFull) continue;   // punto vacío
                point.sampleNumber = static_cast<int64_t>(sample);
                point.streamOffset = static_cast<int64_t>(offset);
                seekTable_.push_back(point);
            }
            // Si el bloque no era múltiplo de 18, se descarta el resto.
            const int32_t leftover = length - points * 18;
            if (leftover > 0) reader_->skipBytes(leftover);
        } else {
            reader_->skipBytes(length);
        }
    }
    if (!haveStreamInfo) {
        if (error != nullptr) *error = "falta el bloque STREAMINFO";
        return false;
    }
    info_.hasSeekTable = !seekTable_.empty();
    info_.audioOffset = reader_->position();
    return true;
}

bool FlacDecoder::decodeResidual(int32_t* out, int blockSize, int order) {
    const int method = static_cast<int>(reader_->readBits(2));
    if (method > 1) return false;   // 2 y 3 están reservados
    const int parameterBits = method == 0 ? 4 : 5;
    const int escape = method == 0 ? 15 : 31;
    const int partitionOrder = static_cast<int>(reader_->readBits(4));
    if (partitionOrder > kMaxRicePartitionOrder) return false;
    const int partitions = 1 << partitionOrder;
    if (order > blockSize || (blockSize % partitions) != 0) return false;
    const int perPartition = blockSize >> partitionOrder;
    int index = 0;
    for (int partition = 0; partition < partitions; ++partition) {
        int count = perPartition;
        if (partition == 0) count -= order;
        if (count < 0) return false;
        const int parameter = static_cast<int>(reader_->readBits(parameterBits));
        if (parameter == escape) {
            const int rawBits = static_cast<int>(reader_->readBits(5));
            for (int i = 0; i < count; ++i) {
                out[index + i] = rawBits == 0
                        ? 0
                        : static_cast<int32_t>(reader_->readSigned(rawBits));
            }
        } else if (parameter == 0) {
            for (int i = 0; i < count; ++i) {
                const uint32_t value = reader_->readUnary();
                out[index + i] = (value & 1u) ? -static_cast<int32_t>(value >> 1) - 1
                                              : static_cast<int32_t>(value >> 1);
            }
        } else {
            for (int i = 0; i < count; ++i) {
                const uint32_t quotient = reader_->readUnary();
                const uint32_t remainder = reader_->readBits(parameter);
                const uint32_t value = (quotient << parameter) | remainder;
                out[index + i] = (value & 1u) ? -static_cast<int32_t>(value >> 1) - 1
                                              : static_cast<int32_t>(value >> 1);
            }
        }
        index += count;
    }
    return true;
}

bool FlacDecoder::decodeSubframe(int channel, int32_t* out, int blockSize, int bitsPerSample) {
    if (reader_->readBits(1) != 0) return false;   // bit de relleno, debe ser 0
    const int type = static_cast<int>(reader_->readBits(6));
    const bool hasWasted = reader_->readBits(1) != 0;
    int wasted = 0;
    if (hasWasted) {
        wasted = static_cast<int>(reader_->readUnary()) + 1;
        if (wasted >= bitsPerSample) return false;
        bitsPerSample -= wasted;
    }

    if (type == 0) {   // CONSTANT
        const int32_t value = static_cast<int32_t>(reader_->readSigned(bitsPerSample));
        for (int i = 0; i < blockSize; ++i) out[i] = value;
    } else if (type == 1) {   // VERBATIM
        for (int i = 0; i < blockSize; ++i) {
            out[i] = static_cast<int32_t>(reader_->readSigned(bitsPerSample));
        }
    } else if ((type & 0x38) == 0x08) {   // FIXED, orden 0..4
        const int order = type & 0x07;
        if (order > 4 || order > blockSize) return false;
        for (int i = 0; i < order; ++i) out[i] = static_cast<int32_t>(reader_->readSigned(bitsPerSample));
        if (!decodeResidual(out + order, blockSize, order)) return false;
        switch (order) {
            case 0:
                break;
            case 1:
                for (int i = 1; i < blockSize; ++i) out[i] += out[i - 1];
                break;
            case 2:
                for (int i = 2; i < blockSize; ++i) out[i] += 2 * out[i - 1] - out[i - 2];
                break;
            case 3:
                for (int i = 3; i < blockSize; ++i) {
                    out[i] += 3 * out[i - 1] - 3 * out[i - 2] + out[i - 3];
                }
                break;
            default:
                for (int i = 4; i < blockSize; ++i) {
                    out[i] += 4 * out[i - 1] - 6 * out[i - 2] + 4 * out[i - 3] - out[i - 4];
                }
                break;
        }
    } else if ((type & 0x20) != 0) {   // LPC, orden 1..32
        const int order = (type & 0x1F) + 1;
        if (order > kMaxLpcOrder || order > blockSize) return false;
        for (int i = 0; i < order; ++i) out[i] = static_cast<int32_t>(reader_->readSigned(bitsPerSample));
        const int precision = static_cast<int>(reader_->readBits(4)) + 1;
        if (precision == 16) return false;   // 1111 está prohibido en la especificación
        const int shift = static_cast<int>(reader_->readSigned(5));
        if (shift < 0) return false;         // RFC 9639: shift negativo es inválido
        int32_t coefficient[kMaxLpcOrder];
        for (int i = 0; i < order; ++i) {
            coefficient[i] = static_cast<int32_t>(reader_->readSigned(precision));
        }
        if (!decodeResidual(out + order, blockSize, order)) return false;
        for (int i = order; i < blockSize; ++i) {
            int64_t sum = 0;
            for (int j = 0; j < order; ++j) {
                sum += static_cast<int64_t>(coefficient[j]) * out[i - 1 - j];
            }
            out[i] += static_cast<int32_t>(sum >> shift);
        }
    } else {
        return false;   // tipo reservado
    }

    if (wasted > 0) {
        for (int i = 0; i < blockSize; ++i) {
            out[i] = static_cast<int32_t>(out[i] << wasted);
        }
    }
    (void)channel;
    return true;
}

bool FlacDecoder::decodeFrame() {
    if (reader_ == nullptr || done_) return false;
    if (reader_->eofReached()) return false;
    reader_->alignToByte();
    const int64_t startOffset = reader_->position();
    if (info_.totalSamples > 0 && framesDecoded_ >= info_.totalSamples) return false;

    reader_->startCrc();

    // --- cabecera ---------------------------------------------------------------
    const uint32_t sync = reader_->readBits(14);
    if (sync != 0x3FFE) return false;
    if (reader_->readBits(1) != 0) return false;   // bit reservado
    (void)reader_->readBits(1);   // blocking strategy: la posición se lleva por cuenta
    const int blockSizeCode = static_cast<int>(reader_->readBits(4));
    const int sampleRateCode = static_cast<int>(reader_->readBits(4));
    channelAssignment_ = static_cast<int>(reader_->readBits(4));
    const int sampleSizeCode = static_cast<int>(reader_->readBits(3));
    if (reader_->readBits(1) != 0) return false;   // bit reservado

    // Número de frame/sample en codificación UTF-8 extendida.
    uint64_t coded = 0;
    const uint8_t first = reader_->readByte();
    int extra = 0;
    if ((first & 0x80) == 0) {
        coded = first;
    } else {
        int mask = 0x40;
        while (mask != 0 && (first & mask) != 0) {
            ++extra;
            mask >>= 1;
        }
        if (extra == 0 || extra > 6) return false;
        coded = first & (0xFFu >> (extra + 1));
        for (int i = 0; i < extra; ++i) {
            const uint8_t next = reader_->readByte();
            if ((next & 0xC0) != 0x80) return false;
            coded = (coded << 6) | (next & 0x3F);
        }
    }

    // Tamaño de bloque.
    if (blockSizeCode == 0) return false;   // reservado
    int32_t blockSize = 0;
    switch (blockSizeCode) {
        case 1: blockSize = 192; break;
        case 2: case 3: case 4: case 5:
            blockSize = 576 << (blockSizeCode - 2);
            break;
        case 6: blockSize = static_cast<int32_t>(reader_->readBits(8)) + 1; break;
        case 7: blockSize = static_cast<int32_t>(reader_->readBits(16)) + 1; break;
        default: blockSize = 256 << (blockSizeCode - 8); break;
    }
    if (blockSize <= 0 || blockSize > info_.maxBlockSize) return false;

    // Profundidad de bits: tiene que coincidir con STREAMINFO (no se re-cuantiza).
    int bps = 0;
    switch (sampleSizeCode) {
        case 0: bps = info_.bitsPerSample; break;
        case 1: bps = 8; break;
        case 2: bps = 12; break;
        case 4: bps = 16; break;
        case 5: bps = 20; break;
        case 6: bps = 24; break;
        case 7: bps = 32; break;
        default: return false;
    }
    if (bps != info_.bitsPerSample) return false;

    // Frecuencia de muestreo.
    int32_t rate = 0;
    if (sampleRateCode == 0) {
        rate = info_.sampleRate;
    } else if (sampleRateCode <= 11) {
        rate = kSampleRateTable[sampleRateCode];
    } else if (sampleRateCode == 12) {
        rate = static_cast<int32_t>(reader_->readBits(8)) * 1000;
    } else if (sampleRateCode == 13) {
        rate = static_cast<int32_t>(reader_->readBits(16));
    } else if (sampleRateCode == 14) {
        rate = static_cast<int32_t>(reader_->readBits(16)) * 10;
    } else {
        return false;
    }
    if (rate != info_.sampleRate) return false;

    // CRC-8 de la cabecera: se compara de verdad. Si no cierra, la cabecera está corrupta
    // y decodificar el resto sólo produce basura.
    const uint8_t headerCrc = reader_->headerCrc8();
    const uint8_t expectedHeaderCrc = reader_->readRawByte();
    if (headerCrc != expectedHeaderCrc) {
        ++crcErrors_;
        if (strictCrc_) return false;
    }
    reader_->feedCrc(expectedHeaderCrc);   // el CRC-8 también entra en el CRC-16 del frame

    const int channels = channelAssignment_ < 8 ? channelAssignment_ + 1 : 2;
    if (channels != info_.channels) return false;

    blockSize_ = blockSize;
    bps_ = bps;
    sampleRate_ = rate;
    channelCount_ = channels;
    frameNumber_ = static_cast<int32_t>(coded);
    // El primer sample del frame es la posición en la que estamos: se lleva la cuenta
    // (no se deriva de `coded`, porque en blocking fijo el número de frame se multiplica
    // por el tamaño de bloque, y el último bloque de un FLAC suele ser más chico: ahí
    // `coded * blockSize` da un valor que ya pasó del final del archivo).
    frameFirstSample_ = framesDecoded_;

    // --- subframes -------------------------------------------------------------
    for (int channel = 0; channel < channels; ++channel) {
        int subframeBits = bps;
        if ((channelAssignment_ == 8 && channel == 1) ||
            (channelAssignment_ == 9 && channel == 0) ||
            (channelAssignment_ == 10 && channel == 1)) {
            subframeBits = bps + 1;   // el canal "diferencia" necesita un bit más
        }
        if (!decodeSubframe(channel, channels_[static_cast<size_t>(channel)].data(), blockSize,
                            subframeBits)) {
            return false;
        }
    }

    // --- decorrelación de canales ---------------------------------------------
    // En mono (channelAssignment_ < 8 y un solo canal) no existe `channels_[1]`: tomar
    // `channels_[1].data()` igual era lectura fuera de rango (lo encontró el arnés con
    // -fsanitize=address).
    int32_t* left = channels_[0].data();
    int32_t* right = channels_.size() > 1 ? channels_[1].data() : nullptr;
    switch (channelAssignment_) {
        case 8:   // izquierda / diferencia
            for (int i = 0; i < blockSize; ++i) right[i] = left[i] - right[i];
            break;
        case 9:   // diferencia / derecha
            for (int i = 0; i < blockSize; ++i) left[i] = right[i] + left[i];
            break;
        case 10:  // medio / lateral
            for (int i = 0; i < blockSize; ++i) {
                const int32_t mid = left[i];
                const int32_t side = right[i];
                const int32_t doubled = (mid << 1) | (side & 1);
                left[i] = (doubled + side) >> 1;
                right[i] = (doubled - side) >> 1;
            }
            break;
        default:
            break;
    }

    // --- CRC-16 del frame ------------------------------------------------------
    reader_->alignToByte();
    const uint16_t computed = reader_->crcNow();
    reader_->suspendCrc();
    const uint16_t stored = reader_->readCrc16();
    reader_->resumeCrc();
    if (computed != stored) {
        ++crcErrors_;
        if (strictCrc_) return false;
    }

    frameStartOffset_ = startOffset;
    frameCursor_ = 0;
    frameSamplesLeft_ = blockSize;
    return true;
}

int32_t FlacDecoder::read(uint8_t* out, int32_t maxFrames, size_t outCapacityBytes) {
    if (out == nullptr || maxFrames <= 0 || reader_ == nullptr) return 0;
    const int32_t bytes = bytesPerSample(info_.bitsPerSample);
    const size_t frameBytes = static_cast<size_t>(bytes) * static_cast<size_t>(info_.channels);
    int32_t limit = maxFrames;
    if (frameBytes > 0) {
        const int32_t byCapacity = static_cast<int32_t>(outCapacityBytes / frameBytes);
        if (byCapacity < limit) limit = byCapacity;
    }
    int32_t written = 0;

    while (written < limit) {
        if (frameSamplesLeft_ <= 0) {
            if (!decodeFrame()) {
                done_ = true;
                break;
            }
        }
        const int32_t take = std::min(frameSamplesLeft_, limit - written);
        if (take <= 0) break;
        for (int32_t i = 0; i < take; ++i) {
            const int32_t sampleIndex = frameCursor_ + i;
            for (int32_t channel = 0; channel < info_.channels; ++channel) {
                const int32_t value = channels_[static_cast<size_t>(channel)]
                                              [static_cast<size_t>(sampleIndex)];
                uint8_t* destination = out + (static_cast<size_t>(written + i) *
                                              static_cast<size_t>(info_.channels) +
                                              static_cast<size_t>(channel)) *
                                             static_cast<size_t>(bytes);
                if (bytes == 2) {
                    const uint16_t raw = static_cast<uint16_t>(static_cast<int16_t>(value));
                    destination[0] = static_cast<uint8_t>(raw & 0xFF);
                    destination[1] = static_cast<uint8_t>((raw >> 8) & 0xFF);
                } else if (bytes == 3) {
                    destination[0] = static_cast<uint8_t>(value & 0xFF);
                    destination[1] = static_cast<uint8_t>((value >> 8) & 0xFF);
                    destination[2] = static_cast<uint8_t>((value >> 16) & 0xFF);
                } else {
                    const uint32_t raw = static_cast<uint32_t>(value);
                    destination[0] = static_cast<uint8_t>(raw & 0xFF);
                    destination[1] = static_cast<uint8_t>((raw >> 8) & 0xFF);
                    destination[2] = static_cast<uint8_t>((raw >> 16) & 0xFF);
                    destination[3] = static_cast<uint8_t>((raw >> 24) & 0xFF);
                }
            }
        }
        frameCursor_ += take;
        frameSamplesLeft_ -= take;
        written += take;
        framesDecoded_ = frameFirstSample_ + frameCursor_;
        if (info_.totalSamples > 0 && framesDecoded_ >= info_.totalSamples) break;
    }
    return written;
}

bool FlacDecoder::seek(int64_t sampleNumber) {
    if (reader_ == nullptr || sampleNumber < 0) return false;
    int64_t target = sampleNumber;
    if (info_.totalSamples > 0) target = std::min(target, info_.totalSamples);

    // Punto de la SEEKTABLE más cercano por debajo del objetivo.
    int64_t startSample = 0;
    int64_t startOffset = info_.audioOffset;
    for (const auto& point : seekTable_) {
        if (point.sampleNumber <= target && point.sampleNumber >= startSample) {
            startSample = point.sampleNumber;
            startOffset = point.streamOffset;
        }
    }
    // Sin SEEKTABLE útil o hacia atrás: se vuelve al primer frame del archivo.
    if (startSample == 0 && target > 0 && seekTable_.empty()) {
        startOffset = info_.audioOffset;
    }
    if (!source_->seek(startOffset)) return false;
    reader_ = std::make_unique<BufferedReader>(*source_);
    frameSamplesLeft_ = 0;
    frameCursor_ = 0;
    done_ = false;
    framesDecoded_ = startSample;
    frameFirstSample_ = startSample;
    blockSize_ = 0;

    // Decodificar y descartar hasta el frame que contiene el objetivo.
    const size_t scratchSize = static_cast<size_t>(info_.maxBlockSize) *
                               static_cast<size_t>(info_.channels) *
                               static_cast<size_t>(bytesPerSample(info_.bitsPerSample)) + 64;
    std::vector<uint8_t> scratch(scratchSize, 0);
    while (framesDecoded_ < target) {
        const int32_t got = read(scratch.data(), info_.maxBlockSize, scratch.size());
        if (got <= 0) return false;
    }
    if (framesDecoded_ > target) {
        // El objetivo cae dentro del frame ya decodificado: se reubica el cursor.
        int64_t offset = target - frameFirstSample_;
        if (offset < 0) offset = 0;
        if (offset > blockSize_) offset = blockSize_;
        frameCursor_ = static_cast<int32_t>(offset);
        frameSamplesLeft_ = blockSize_ - frameCursor_;
        framesDecoded_ = target;
    }
    return true;
}

// ---------------------------------------------------------------- índice de frames

namespace {

/**
 * Lee una cabecera de frame desde `reader` y devuelve el tamaño de su bloque. No decodifica
 * subframes: se salta el frame completo (se conoce su longitud por la cabecera siguiente,
 * no por la actual, así que el que llama encadena los offsets). Devuelve -1 si no hay una
 * cabecera válida en la posición actual (fin del stream o basura).
 */
int32_t parseFrameHeader(BufferedReader& reader, const FlacStreamInfo& info,
                         int64_t* codedNumber, bool* variable) {
    reader.startCrc();
    if (reader.readBits(14) != 0x3FFE) return -1;
    if (reader.readBits(1) != 0) return -1;
    *variable = reader.readBits(1) != 0;
    const int blockSizeCode = static_cast<int>(reader.readBits(4));
    const int sampleRateCode = static_cast<int>(reader.readBits(4));
    const int channelAssignment = static_cast<int>(reader.readBits(4));
    const int sampleSizeCode = static_cast<int>(reader.readBits(3));
    if (reader.readBits(1) != 0) return -1;

    uint64_t coded = 0;
    const uint8_t first = reader.readByte();
    int extra = 0;
    if ((first & 0x80) == 0) {
        coded = first;
    } else {
        int mask = 0x40;
        while (mask != 0 && (first & mask) != 0) {
            ++extra;
            mask >>= 1;
        }
        if (extra == 0 || extra > 6) return -1;
        coded = first & (0xFFu >> (extra + 1));
        for (int i = 0; i < extra; ++i) {
            const uint8_t next = reader.readByte();
            if ((next & 0xC0) != 0x80) return -1;
            coded = (coded << 6) | (next & 0x3F);
        }
    }
    *codedNumber = static_cast<int64_t>(coded);

    if (blockSizeCode == 0) return -1;
    int32_t blockSize = 0;
    switch (blockSizeCode) {
        case 1: blockSize = 192; break;
        case 2: case 3: case 4: case 5: blockSize = 576 << (blockSizeCode - 2); break;
        case 6: blockSize = static_cast<int32_t>(reader.readBits(8)) + 1; break;
        case 7: blockSize = static_cast<int32_t>(reader.readBits(16)) + 1; break;
        default: blockSize = 256 << (blockSizeCode - 8); break;
    }
    if (blockSize <= 0 || blockSize > info.maxBlockSize) return -1;

    if (sampleRateCode == 12) {
        reader.readBits(8);
    } else if (sampleRateCode == 13 || sampleRateCode == 14) {
        reader.readBits(16);
    } else if (sampleRateCode > 14) {
        return -1;
    }
    // Profundidad y asignación de canales: se validan contra STREAMINFO para no aceptar
    // basura como si fuera una cabecera.
    const int channels = channelAssignment < 8 ? channelAssignment + 1 : 2;
    if (channels != info.channels) return -1;
    if (sampleSizeCode != 0) {
        const int bps = sampleSizeCode == 1 ? 8 : sampleSizeCode == 2 ? 12
                      : sampleSizeCode == 4 ? 16 : sampleSizeCode == 5 ? 20
                      : sampleSizeCode == 6 ? 24 : sampleSizeCode == 7 ? 32 : 0;
        if (bps != info.bitsPerSample) return -1;
    }

    const uint8_t headerCrc = reader.headerCrc8();
    const uint8_t storedCrc = reader.readRawByte();
    if (headerCrc != storedCrc) return -1;
    return blockSize;
}

/**
 * Busca la próxima cabecera de frame a partir de `start`. Un frame de FLAC no lleva su
 * longitud al principio, así que se escanea hasta encontrar el patrón de sync
 * (0xFF 0xF8/0xF9) y se valida la cabecera completa (incluido su CRC-8) antes de aceptarla:
 * así un 0xFF que aparece dentro del audio comprimido no se confunde con un frame.
 */
int64_t findNextFrame(ByteSource& source, const FlacStreamInfo& info, int64_t start) {
    if (!source.seek(start)) return -1;
    BufferedReader reader(source);
    while (true) {
        const int64_t candidate = reader.position();
        const uint8_t byte = reader.readByte();
        if (reader.eofReached()) return -1;
        if (byte != 0xFF) continue;
        const uint8_t second = reader.peekByte();
        if (reader.eofReached()) return -1;
        if ((second & 0xFE) != 0xF8) continue;
        if (!source.seek(candidate)) return -1;
        BufferedReader probe(source);
        int64_t coded = 0;
        bool variable = false;
        if (parseFrameHeader(probe, info, &coded, &variable) > 0) return candidate;
        // Falso positivo: se sigue desde el byte siguiente.
        if (!source.seek(candidate + 1)) return -1;
        return findNextFrame(source, info, candidate + 1);
    }
}

}  // namespace

bool FlacDecoder::buildFrameIndex(ByteSource& source, const FlacStreamInfo& info,
                                  std::vector<FlacFrameIndexEntry>* out, std::string* error) {
    if (out == nullptr) {
        if (error != nullptr) *error = "índice nulo";
        return false;
    }
    out->clear();
    int64_t offset = info.audioOffset;
    int64_t sample = 0;
    while (sample < (info.totalSamples > 0 ? info.totalSamples : INT64_MAX)) {
        if (!source.seek(offset)) break;
        BufferedReader reader(source);
        int64_t coded = 0;
        bool variable = false;
        const int32_t blockSize = parseFrameHeader(reader, info, &coded, &variable);
        if (blockSize < 0) break;   // no hay una cabecera válida acá: se termina el índice
        out->push_back({offset, sample, blockSize});
        sample += blockSize;
        if (info.totalSamples > 0 && sample >= info.totalSamples) break;
        const int64_t next = findNextFrame(source, info, offset + 1);
        if (next < 0) break;
        offset = next;
    }
    if (out->empty()) {
        if (error != nullptr) *error = "no se encontró ningún frame";
        return false;
    }
    return true;
}

}  // namespace audio
