cmake_minimum_required(VERSION 3.10)

project(realesrgan_jni LANGUAGES CXX)

add_library(realesrgan_jni SHARED realesrgan_jni.cpp)

target_include_directories(realesrgan_jni PRIVATE
    ${CMAKE_SOURCE_DIR}/ncnn/include
)

target_link_libraries(realesrgan_jni
    android
    jnigraphics
    log
)

target_compile_options(realesrgan_jni PRIVATE
    -frtti
    -fexceptions
)
target_link_options(realesrgan_jni PRIVATE
    -Wl,--unresolved-symbols=allow-all
)

set_target_properties(realesrgan_jni PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    POSITION_INDEPENDENT_CODE ON
)
