cmake_minimum_required(VERSION 3.22.1)
project(orin_aec C)

add_library(orin_aec SHARED
    aec_jni.c
    speexdsp/mdf.c
    speexdsp/preprocess.c
    speexdsp/filterbank.c
    speexdsp/fftwrap.c
    speexdsp/kiss_fft.c
    speexdsp/kiss_fftr.c)
target_include_directories(orin_aec PRIVATE speexdsp speexdsp/include)
target_compile_definitions(orin_aec PRIVATE HAVE_CONFIG_H)
target_compile_options(orin_aec PRIVATE -O2 -ffast-math -Wno-unused-parameter)
target_link_libraries(orin_aec m)
target_link_options(orin_aec PRIVATE "-Wl,-z,max-page-size=16384")
