#pragma once
#include <cstddef>
#include <cstdint>
#include <string>

namespace fluxx::text {
// Untrusted font bytes: Android callers must execute this in the isolated font worker.
// Host fixtures run in the standalone proof process, never inside the Android app.
std::string inspectFont(const uint8_t* bytes,size_t length);
}
