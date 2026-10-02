# JarvisAlgo only: the on-device model runner. llama.cpp is fetched at a fixed release (b6800) and built for arm64 with
# the dot-product and half-precision instructions; the app checks the phone's CPU has both before it loads this.
cmake_minimum_required(VERSION 3.22.1)
project(jarvis_llm C CXX)

include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)            # no network code: the app downloads the model itself
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
FetchContent_Declare(llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp
    GIT_TAG        b6800
    GIT_SHALLOW    TRUE)
FetchContent_MakeAvailable(llama)

add_library(jarvis_llm SHARED jarvis_llm.cpp)
target_link_libraries(jarvis_llm llama ggml)
