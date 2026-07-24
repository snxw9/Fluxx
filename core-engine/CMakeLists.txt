cmake_minimum_required(VERSION 3.22.1)

project("fluxxengine")

# Find Android logging library
find_library(log-lib log)

# Compiler configuration
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Define native library target (libfluxxengine.so)
add_library(fluxxengine SHARED
    src/main/cpp/jni_bridge.cpp
)

# Link logging library
target_link_libraries(fluxxengine PRIVATE
    ${log-lib}
)
