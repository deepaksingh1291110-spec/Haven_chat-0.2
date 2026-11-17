cmake_minimum_required(VERSION 3.22.1)

project("haven-engine" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# CPU only, arm64-v8a only (no GPU backends are built at all).
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Haven Chat builds for arm64-v8a only (got: '${ANDROID_ABI}')")
endif()
set(GGML_SYSTEM_ARCH "ARM")
set(GGML_CPU_KLEIDIAI ON)
set(GGML_OPENMP ON)

# llama.cpp is NOT stored in this repo. The GitHub Actions workflow clones a
# pinned release into third_party/llama.cpp before the build starts.
get_filename_component(LLAMA_SRC
        "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp" ABSOLUTE)

if(NOT EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_SRC}. "
            "The workflow step 'Get llama.cpp' must run before the build.")
endif()

add_subdirectory(${LLAMA_SRC} build-llama)

# Our own JNI library: loads models, chats, (later) embeds and reranks.
add_library(haven-engine SHARED
        haven_engine.cpp)

target_include_directories(haven-engine PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(haven-engine
        llama
        llama-common
        android
        log)
