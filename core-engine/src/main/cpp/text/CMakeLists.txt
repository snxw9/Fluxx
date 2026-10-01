# Shared by the Android build and the CPU-only host verification harness.
set(TEXT_VENDOR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party")

function(fluxx_font_dependencies)
    # Keep upstream warnings visible, but Fluxx's -Werror is for Fluxx-owned sources.
    # Remove only the standalone project switch, retaining NDK -Werror=format-security.
    string(REGEX REPLACE "(^|[ \t])-Werror([ \t]|$)" "\\1\\2" CMAKE_C_FLAGS "${CMAKE_C_FLAGS}")
    string(REGEX REPLACE "(^|[ \t])-Werror([ \t]|$)" "\\1\\2" CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}")
    set(BUILD_SHARED_LIBS OFF)
    set(FT_DISABLE_ZLIB ON CACHE BOOL "No compressed fonts" FORCE)
    set(FT_DISABLE_BZIP2 ON CACHE BOOL "No compressed fonts" FORCE)
    set(FT_DISABLE_PNG ON CACHE BOOL "No color bitmap fonts" FORCE)
    set(FT_DISABLE_BROTLI ON CACHE BOOL "No WOFF2" FORCE)
    set(FT_DISABLE_HARFBUZZ ON CACHE BOOL "No circular dependency" FORCE)
    add_subdirectory("${TEXT_VENDOR}/freetype" "${CMAKE_CURRENT_BINARY_DIR}/freetype")
    set_target_properties(freetype PROPERTIES POSITION_INDEPENDENT_CODE ON)

    add_library(fluxx_harfbuzz STATIC "${TEXT_VENDOR}/harfbuzz/src/harfbuzz.cc")
    target_include_directories(fluxx_harfbuzz PUBLIC "${TEXT_VENDOR}/harfbuzz/src")
    target_compile_features(fluxx_harfbuzz PUBLIC cxx_std_17)
    target_compile_definitions(fluxx_harfbuzz PRIVATE
        HB_NO_AAT HB_NO_BITMAP HB_NO_COLOR HB_NO_DRAW
        HB_NO_MATH HB_NO_META HB_NO_VAR HB_NO_VERTICAL HB_NO_CFF HB_NO_GETENV)
    set_target_properties(fluxx_harfbuzz PROPERTIES POSITION_INDEPENDENT_CODE ON)
endfunction()
fluxx_font_dependencies()

add_library(fluxx_text STATIC font_manager.cpp atlas_packer.cpp)
target_link_libraries(fluxx_text PUBLIC freetype fluxx_harfbuzz)
target_include_directories(fluxx_text PUBLIC "${CMAKE_CURRENT_LIST_DIR}")
target_compile_features(fluxx_text PUBLIC cxx_std_17)
set_target_properties(fluxx_text PROPERTIES POSITION_INDEPENDENT_CODE ON)
if(MSVC)
    target_compile_options(fluxx_text PRIVATE /W4 /WX /utf-8 /EHsc)
else()
    target_compile_options(fluxx_text PRIVATE -Wall -Wextra -Werror)
endif()
