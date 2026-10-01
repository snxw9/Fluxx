#pragma once
#include "font_manager.h"
#include <filesystem>
namespace fluxx::text {
// Built only into debug Android variants and the standalone CPU test executable.
void dumpDiagnostics(FontManager& manager, const std::filesystem::path& directory);
void writeGrayPng(const std::filesystem::path& path, int width, int height, const std::vector<uint8_t>& pixels);
}
