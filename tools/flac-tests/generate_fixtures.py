#!/usr/bin/env python3
"""Genera los fixtures del arnés del decodificador FLAC.

Para cada combinación de profundidad / frecuencia / canales escribe:

  test_<bits>_<rate>_<channels>.flac  el archivo comprimido
  test_<bits>_<rate>_<channels>.raw   el PCM intercalado little-endian que libFLAC
                                      (vía libsndfile) considera correcto

La señal se arma a propósito para ejercitar todos los caminos del decodificador:

  - una rampa: el predictor queda perfecto y el codificador elige FIXED/LPC corto;
  - silencio: bloques CONSTANT y particiones Rice con parámetro 0;
  - dos tonos + ruido: LPC de orden alto (es el caso de un FLAC real);
  - amplitud casi a fondo de escala: verifica que no haya recorte ni desplazamiento.

El `.raw` es la referencia: si el decodificador propio no coincide byte a byte con lo que
produce libFLAC, el arnés falla.
"""
import os
import sys

import numpy as np
import soundfile as sf

COMBINATIONS = [
    (16, 44100, 1),
    (16, 44100, 2),
    (16, 48000, 2),
    (16, 96000, 2),
    (24, 44100, 2),
    (24, 48000, 1),
    (24, 48000, 2),
    (24, 96000, 2),
]


def build_signal(rate: int, channels: int, bits: int) -> np.ndarray:
    rng = np.random.default_rng(seed=rate + channels * 7 + bits)
    n = rate // 2  # medio segundo: suficiente para varios frames de bloque variable
    t = np.arange(n, dtype=np.float64) / rate

    mono = np.zeros(n, dtype=np.float64)
    # 1) rampa (predictor perfecto -> fixed/lpc de orden bajo)
    ramp = min(n // 8, rate // 20)
    mono[:ramp] = np.linspace(-0.8, 0.8, ramp)
    # 2) dos tonos bien separados + ruido bajo (lpc de orden alto, como un FLAC real)
    body = slice(ramp, min(n, ramp + rate // 4))
    mono[body] = (
        0.55 * np.sin(2 * np.pi * 440.0 * t[body])
        + 0.30 * np.sin(2 * np.pi * 7300.0 * t[body])
        + 0.002 * rng.standard_normal(body.stop - body.start)
    )
    # 3) silencio digital exacto (bloques constant -> CONSTANT subframe)
    silent = slice(body.stop, min(n, body.stop + rate // 20))
    mono[silent] = 0.0
    # 4) ruido a fondo de escala (verbatim o rice con parámetro alto)
    loud = slice(silent.stop, min(n, silent.stop + rate // 20))
    mono[loud] = 0.98 * rng.standard_normal(loud.stop - loud.start) / 3.0
    # 5) resto: sweep suave
    rest = slice(loud.stop, n)
    if rest.stop > rest.start:
        freq = np.linspace(60.0, min(18000.0, rate / 2 - 500.0), rest.stop - rest.start)
        phase = 2 * np.pi * np.cumsum(freq) / rate
        mono[rest] = 0.7 * np.sin(phase)

    mono = np.clip(mono, -1.0, 1.0)
    if channels == 1:
        return mono[:, None].copy()
    # Estéreo distinto por canal para forzar los modos izq/der, izq-lado y medio-lado.
    right = np.clip(mono * 0.75 + 0.2 * np.sin(2 * np.pi * 220.0 * t), -1.0, 1.0)
    return np.stack([mono, right], axis=1)


def main() -> int:
    directory = sys.argv[1] if len(sys.argv) > 1 else "fixtures"
    os.makedirs(directory, exist_ok=True)
    written = 0
    for bits, rate, channels in COMBINATIONS:
        signal = build_signal(rate, channels, bits)
        subtype = f"PCM_{bits}"
        base = os.path.join(directory, f"test_{bits}_{rate}_{channels}")
        sf.write(base + ".flac", signal, rate, subtype=subtype, format="FLAC")
        # Referencia: el mismo archivo leído por libsndfile (libFLAC), en enteros.
        data, read_rate = sf.read(base + ".flac", dtype="int32", always_2d=True)
        if read_rate != rate:
            raise SystemExit(f"rate inesperado en {base}.flac")
        if bits == 16:
            # libsndfile entrega int32 con los 16 bits en la parte alta: se baja a int16.
            pcm = (data >> 16).astype("<i2")
        elif bits == 24:
            # 24 bits: libsndfile escala a int32 (valor << 8). Se guardan 3 bytes LE.
            packed = (data >> 8).astype("<i4")
            raw = packed.tobytes()
            # De 4 bytes por muestra a 3 (little-endian).
            view = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 4)
            pcm = view[:, :3].tobytes()
            with open(base + ".raw", "wb") as handle:
                handle.write(pcm)
            written += 1
            continue
        else:
            raise SystemExit("profundidad no soportada en el generador")
        with open(base + ".raw", "wb") as handle:
            handle.write(pcm.tobytes())
        written += 1
    print(f"{written} fixtures en {directory}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
