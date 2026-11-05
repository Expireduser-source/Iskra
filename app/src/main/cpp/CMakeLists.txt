# The model on the phone: llama.cpp (CPU only) linked statically into one small JNI library.
# LLAMA_SRC is the llama.cpp source folder; the Gradle task "fetchLlamaCpp" downloads and checks it.
cmake_minimum_required(VERSION 3.22.1)
project(iskra_llama C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)   # static llama.cpp goes into a shared library

if(NOT DEFINED LLAMA_SRC OR NOT EXISTS "${LLAMA_SRC}/include/llama.h")
    message(FATAL_ERROR "LLAMA_SRC must point to llama.cpp sources (got '${LLAMA_SRC}')")
endif()

# Only the library itself: no tools, servers, tests, network or GPU code.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
foreach(opt LLAMA_BUILD_COMMON LLAMA_BUILD_TESTS LLAMA_BUILD_TOOLS LLAMA_BUILD_EXAMPLES LLAMA_BUILD_SERVER LLAMA_BUILD_APP
        LLAMA_OPENSSL LLAMA_CURL GGML_NATIVE GGML_OPENMP GGML_BACKEND_DL GGML_CPU_ALL_VARIANTS GGML_CPU_KLEIDIAI)
    set(${opt} OFF CACHE BOOL "" FORCE)
endforeach()
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Dot-product and half-precision instructions: every phone core since Cortex-A55/A75 (2018). The app checks
    # /proc/cpuinfo for them before loading this library, so older phones never run it.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

add_subdirectory(${LLAMA_SRC} llama.cpp EXCLUDE_FROM_ALL)

add_library(iskra_llama SHARED iskra_llama.cpp)
target_link_libraries(iskra_llama PRIVATE llama)
if(ANDROID)
    target_link_libraries(iskra_llama PRIVATE log)
    target_link_options(iskra_llama PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
else()
    find_package(JNI REQUIRED)
    target_include_directories(iskra_llama PRIVATE ${JNI_INCLUDE_DIRS})
endif()
