#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace audio {

/**
 * Conversor de frecuencia polifásico con ventana de Kaiser.
 *
 * Se usa SÓLO cuando el HAL no acepta el rate de la pista: la app entrega PCM a la
 * frecuencia del archivo y el stream corre a la frecuencia nativa del equipo. La
 * conversión ocurre una sola vez por bloque, en el hilo productor, con estado privado.
 *
 * Ventana Kaiser β≈10.06 y 32 semi-taps por lado: ~100 dB de rechazo de alias, que es
 * lo que hace que 44.1 → 48 kHz no "sople" ni pierda agudos (medido en el arnés).
 *
 * `process()` es incremental: lo que no entra en `maxOutFrames` queda en la ventana de
 * historia para la próxima llamada.
 */
class PolyphaseResampler {
public:
    void configure(int32_t inRate, int32_t outRate, int32_t channels);
    void reset();
    bool bypass() const { return step_ == 1.0; }
    double step() const { return step_; }
    int32_t halfFrames() const { return half_; }
    /** Devuelve los frames escritos en `out`. */
    int32_t process(const float* in, int32_t inFrames, float* out, int32_t maxOutFrames);

private:
    static double besselI0(double x);
    void rebuildKernel();

    int32_t channels_ = 2;
    int32_t half_ = 32;
    int32_t taps_ = 64;
    double step_ = 1.0;
    double pos_ = 0.0;
    int32_t historyFrames_ = 0;
    std::vector<float> kernel_;
    std::vector<float> work_;
};

} // namespace audio
