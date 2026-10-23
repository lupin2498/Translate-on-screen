# Thư viện AI dịch trên máy (libscreenai.so): llama.cpp + mtmd (đọc ảnh) + cầu nối JNI.
#
# Android: được Gradle gọi qua externalNativeBuild.
# Máy tính (kiểm thử với mô hình thật trên JVM):
#   cmake -S app/src/main/cpp -B build-host -DLLAMA_SRC_DIR=/duong/dan/llama.cpp
#   cmake --build build-host -j
cmake_minimum_required(VERSION 3.22)
project(screenai C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Máy 32-bit (armeabi-v7a) không đủ bộ nhớ cho mô hình > 1 GB: chỉ dựng thư viện giả để
# System.loadLibrary vẫn thành công và ứng dụng báo "máy không hỗ trợ AI trên máy".
if (ANDROID AND ANDROID_ABI STREQUAL "armeabi-v7a")
    add_library(screenai SHARED screenai_jni.cpp)
    target_compile_definitions(screenai PRIVATE SCREENAI_STUB=1)
    target_link_libraries(screenai log)
    return()
endif()

# Phiên bản llama.cpp cố định (cùng API với screenai_jni.cpp)
set(LLAMA_TAG "b11829" CACHE STRING "llama.cpp release tag")

set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD     ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS     OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS   OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO           OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    ON  CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)

if (ANDROID)
    # Một APK chạy trên mọi đời chip: dựng mọi biến thể CPU (dotprod, i8mm, SVE, SME...) và
    # chọn biến thể nhanh nhất lúc chạy.
    set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL       ON  CACHE BOOL "" FORCE)
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
        set(GGML_CPU_KLEIDIAI     ON CACHE BOOL "" FORCE)
    else()
        # x86_64 chỉ dùng cho emulator kiểm thử: một biến thể CPU là đủ
        set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
        set(GGML_CPU_KLEIDIAI     OFF CACHE BOOL "" FORCE)
    endif()
endif()

if (LLAMA_SRC_DIR)
    add_subdirectory(${LLAMA_SRC_DIR} llama-build)
else()
    include(FetchContent)
    if (POLICY CMP0135)
        cmake_policy(SET CMP0135 NEW)
    endif()
    FetchContent_Declare(llama_cpp
        URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LLAMA_TAG}.tar.gz)
    FetchContent_MakeAvailable(llama_cpp)
endif()

add_library(screenai SHARED screenai_jni.cpp)
target_link_libraries(screenai PRIVATE llama llama-common mtmd)

if (ANDROID)
    target_link_libraries(screenai PRIVATE android log)
else()
    find_package(JNI REQUIRED)
    target_include_directories(screenai PRIVATE ${JNI_INCLUDE_DIRS})
endif()
