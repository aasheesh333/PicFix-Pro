cmake_minimum_required(VERSION 3.10)

project(realesrgan_jni LANGUAGES CXX)

add_library(realesrgan_jni SHARED realesrgan_jni.cpp)

target_include_directories(realesrgan_jni PRIVATE
    ${CMAKE_SOURCE_DIR}/ncnn/include
)

set(NCNN_LIB_DIR "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
add_library(ncnn SHARED IMPORTED)
set_target_properties(ncnn PROPERTIES
    IMPORTED_LOCATION "${NCNN_LIB_DIR}/libncnn.so"
)

target_link_libraries(realesrgan_jni
    ncnn
    android
    jnigraphics
    log
)

target_compile_options(realesrgan_jni PRIVATE
    -frtti
    -fexceptions
)

set_target_properties(realesrgan_jni PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    POSITION_INDEPENDENT_CODE ON
)
