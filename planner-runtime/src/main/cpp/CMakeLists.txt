cmake_minimum_required(VERSION 3.22.1)
project(ai_image_planner)

get_filename_component(REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)
set(LLAMA_ROOT "${REPO_ROOT}/third_party/llama.cpp")
if (NOT EXISTS "${LLAMA_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp is missing; initialize Git submodules")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# Android debug APKs are used for device profiling, but llama/ggml must never be
# compiled with the unoptimized CMake Debug defaults. Both target phones use
# ARMv8.2 cores with FP16 and dot-product support.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -DNDEBUG -march=armv8.2-a+dotprod+fp16")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG -march=armv8.2-a+dotprod+fp16")
add_subdirectory("${LLAMA_ROOT}" "${CMAKE_BINARY_DIR}/llama")

add_library(planner_jni SHARED planner_bridge.cpp)
target_include_directories(planner_jni PRIVATE "${LLAMA_ROOT}/include")
target_link_libraries(planner_jni PRIVATE llama log android)
target_link_options(planner_jni PRIVATE "-Wl,--exclude-libs,ALL")
