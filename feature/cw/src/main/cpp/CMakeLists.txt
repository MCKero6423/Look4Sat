cmake_minimum_required(VERSION 3.22.1)
project(fldigi_cw)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 禁用 ANR 检测器等不必要的 Android 特性
set(CMAKE_ANDROID_EXCEPTIONS ON)

add_library(fldigi_cw SHARED
    fldigi_cw_jni.cpp
    fldigi/cw.cxx
    fldigi/morse.cxx
    fldigi/fftfilt.cxx
    fldigi/filters.cxx
)

target_include_directories(fldigi_cw PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/fldigi
)

target_compile_definitions(fldigi_cw PRIVATE
    FLDIGI_ANDROID
)

target_link_libraries(fldigi_cw
    log
    android
)