cmake_minimum_required(VERSION 3.22.1)
project(audioengine)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Oboe is consumed as a prebuilt prefab module (com.google.oboe:oboe AAR).
find_package(oboe REQUIRED CONFIG)

# Oboe trae OboeExtensions.h (consulta de MMAP) en el mismo paquete prefab.

add_library(audioengine SHARED
    native-lib.cpp
    audio/AudioEngine.cpp
    # Conversor de frecuencia polifásico: sólo se usa cuando el HAL no acepta el rate
    # de la pista, nunca "de yapa" en el camino directo.
    audio/Resampler.cpp
    # Decodificador FLAC propio: es lo que permite que un FLAC de 24 bits llegue entero al
    # DAC (MediaCodec sólo entrega float o 16 bits). Sin dependencias externas.
    audio/FlacDecoder.cpp
    flac-jni.cpp
)

target_include_directories(audioengine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_options(audioengine PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -fno-exceptions
    -fno-rtti
)

target_link_libraries(audioengine
    oboe::oboe
    android
    log
)
