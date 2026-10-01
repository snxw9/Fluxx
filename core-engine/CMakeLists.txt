cmake_minimum_required(VERSION 3.22.1)

project("fluxxengine")

# Find Android system libraries
find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)
find_library(jnigraphics-lib jnigraphics)

# Keep the original prebuilt only for the explicit r26 regression baseline.
option(FLUXX_USE_PREBUILT_OBOE "E1a r26 baseline with the original Oboe AAR" OFF)
if(FLUXX_USE_PREBUILT_OBOE)
    find_package(oboe REQUIRED CONFIG)
else()
    # Rebuild the same Oboe version: the AAR's arm64 RELRO end is not 16 KB aligned.
    set(BUILD_SHARED_LIBS OFF)
    add_subdirectory(third_party/oboe)
    set_target_properties(oboe PROPERTIES POSITION_INDEPENDENT_CODE ON)
    add_library(oboe::oboe ALIAS oboe)

    # The transitive graphics-path AAR also has an unaligned RELRO endpoint.
    # Its matching Kotlin sources/proguard rules are included by core-engine's Gradle config.
    add_subdirectory(third_party/graphics-path/src/main/cpp)
    target_compile_features(androidx.graphics.path PRIVATE cxx_std_17)
    # Preserve upstream code-generation options; place linker flags on the link command.
    target_compile_options(androidx.graphics.path PRIVATE
        -fno-exceptions -fno-unwind-tables -fno-asynchronous-unwind-tables -fno-rtti
        -ffast-math -ffp-contract=fast -fvisibility-inlines-hidden -fvisibility=hidden
        -fomit-frame-pointer -ffunction-sections -fdata-sections)
    target_link_options(androidx.graphics.path PRIVATE
        "-Wl,--hash-style=both" "-Wl,--gc-sections" "-Wl,-Bsymbolic-functions")
endif()

# Compiler configuration
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# E1b/E1c are CPU-only. No renderer, Vulkan atlas or text draw path is added.
add_subdirectory(src/main/cpp/text)

# Define native library target (libfluxxengine.so)
add_library(fluxxengine SHARED
    src/main/cpp/jni_bridge.cpp
    src/main/cpp/engine_core.cpp
    src/main/cpp/audio_processor.cpp
    src/main/cpp/vulkan_renderer.cpp
    src/main/cpp/text/text_jni.cpp
    src/main/cpp/text/text_renderer.cpp
)

# Generate SPIR-V into the build tree; user builds compile the checked-in GLSL.
if(CMAKE_HOST_WIN32)
    set(FLUXX_GLSLC "${ANDROID_NDK}/shader-tools/windows-x86_64/glslc.exe")
elseif(CMAKE_HOST_APPLE)
    set(FLUXX_GLSLC "${ANDROID_NDK}/shader-tools/darwin-x86_64/glslc")
else()
    set(FLUXX_GLSLC "${ANDROID_NDK}/shader-tools/linux-x86_64/glslc")
endif()
set(TEXT_SHADER_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/text-shaders")
foreach(stage vert frag)
    add_custom_command(OUTPUT "${TEXT_SHADER_DIRECTORY}/text.${stage}.spv"
        COMMAND ${CMAKE_COMMAND} -E make_directory "${TEXT_SHADER_DIRECTORY}"
        COMMAND "${FLUXX_GLSLC}" --target-env=vulkan1.1
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/assets/shaders/text.${stage}"
            -o "${TEXT_SHADER_DIRECTORY}/text.${stage}.spv"
        DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/src/main/assets/shaders/text.${stage}" VERBATIM)
endforeach()
add_custom_command(OUTPUT "${TEXT_SHADER_DIRECTORY}/text_shaders.h"
    COMMAND ${CMAKE_COMMAND} "-DINPUT=${TEXT_SHADER_DIRECTORY}"
        "-DOUTPUT=${TEXT_SHADER_DIRECTORY}/text_shaders.h"
        -P "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/text/embed_shaders.cmake"
    DEPENDS "${TEXT_SHADER_DIRECTORY}/text.vert.spv" "${TEXT_SHADER_DIRECTORY}/text.frag.spv"
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/text/embed_shaders.cmake" VERBATIM)
add_custom_target(fluxx_text_shaders DEPENDS "${TEXT_SHADER_DIRECTORY}/text_shaders.h")
add_dependencies(fluxxengine fluxx_text_shaders)
target_include_directories(fluxxengine PRIVATE "${TEXT_SHADER_DIRECTORY}")

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_sources(fluxx_text PRIVATE src/main/cpp/text/text_diagnostics.cpp)
    target_compile_definitions(fluxxengine PRIVATE FLUXX_TEXT_DIAGNOSTICS=1)
endif()

# Include C++ directory for header resolution
# E1a: explicit even though r28 defaults to 16 KB for both supported ABIs.
if(NOT FLUXX_USE_PREBUILT_OBOE)
    target_link_options(fluxxengine PRIVATE "-Wl,-z,max-page-size=16384")
endif()

target_include_directories(fluxxengine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp
)

# Link logging, android, vulkan, and oboe libraries
target_link_libraries(fluxxengine PRIVATE
    fluxx_text
    oboe::oboe
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
    ${jnigraphics-lib}
)
