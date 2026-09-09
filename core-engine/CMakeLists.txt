cmake_minimum_required(VERSION 3.22.1)

project("fluxxengine")

# Find Android system libraries
find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)

# Find Oboe via Prefab
find_package(oboe REQUIRED CONFIG)

# Compiler configuration
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Define native library target (libfluxxengine.so)
add_library(fluxxengine SHARED
    src/main/cpp/jni_bridge.cpp
    src/main/cpp/engine_core.cpp
    src/main/cpp/audio_processor.cpp
    src/main/cpp/vulkan_renderer.cpp
)

# Include C++ directory for header resolution
target_include_directories(fluxxengine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp
)

# Link logging, android, vulkan, and oboe libraries
target_link_libraries(fluxxengine PRIVATE
    oboe::oboe
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
)

