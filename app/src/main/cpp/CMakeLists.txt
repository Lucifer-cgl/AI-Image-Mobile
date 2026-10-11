cmake_minimum_required(VERSION 3.22.1)
project(ai_image_mobile)

get_filename_component(REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)
set(SD_SOURCE_DIR "${REPO_ROOT}/third_party/stable-diffusion.cpp")
set(VULKAN_HEADERS_DIR "${REPO_ROOT}/third_party/Vulkan-Headers/include")
set(SPIRV_HEADERS_DIR "${REPO_ROOT}/third_party/SPIRV-Headers/include")
set("SPIRV-Headers_DIR" "${REPO_ROOT}/cmake" CACHE PATH "" FORCE)

if (NOT EXISTS "${SD_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "stable-diffusion.cpp is missing; initialize Git submodules")
endif()

set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
# glslc is supplied by the selected Android NDK. Android Gradle Plugin exposes
# the NDK root to CMake, keeping local and CI builds independent of drive paths.
if (DEFINED ANDROID_NDK)
    if (CMAKE_HOST_WIN32)
        set(_HOST_TAG windows-x86_64)
        set(_GLSLC_SUFFIX .exe)
    elseif(CMAKE_HOST_APPLE)
        set(_HOST_TAG darwin-x86_64)
        set(_GLSLC_SUFFIX "")
    else()
        set(_HOST_TAG linux-x86_64)
        set(_GLSLC_SUFFIX "")
    endif()
    set(Vulkan_GLSLC_EXECUTABLE
        "${ANDROID_NDK}/shader-tools/${_HOST_TAG}/glslc${_GLSLC_SUFFIX}"
        CACHE FILEPATH "" FORCE)
endif()
set(SD_VULKAN ON CACHE BOOL "" FORCE)
set(SD_OPENCL OFF CACHE BOOL "" FORCE)
set(SD_CUDA OFF CACHE BOOL "" FORCE)
add_subdirectory(${SD_SOURCE_DIR} ${CMAKE_BINARY_DIR}/stable-diffusion)

# Android NDK ships Vulkan's C headers but not vulkan.hpp, which the ggml
# Vulkan backend uses. Keep the official Khronos headers outside the APK and
# add them only while compiling the native backend.
target_include_directories(ggml-vulkan PRIVATE
    "${VULKAN_HEADERS_DIR}"
    "${SPIRV_HEADERS_DIR}"
)

add_library(mobile_image_jni SHARED native_bridge.cpp)
target_include_directories(mobile_image_jni PRIVATE ${SD_SOURCE_DIR}/include)
target_link_libraries(mobile_image_jni PRIVATE stable-diffusion log android)
