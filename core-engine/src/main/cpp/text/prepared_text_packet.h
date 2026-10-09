#pragma once
#include "font_manager.h"

namespace fluxx::text {
// Preparation-only transport. Not a project schema or a frame/draw ABI.
constexpr size_t MaxPreparedPacketBytes=32*1024*1024;
constexpr uint32_t MaxPreparedGlyphs=16384;
std::vector<uint8_t> encodePreparedText(const Layout& layout,FontManager& fonts);
std::vector<std::shared_ptr<const SdfGlyph>> decodeSdfSeeds(const uint8_t* bytes,size_t length);
std::shared_ptr<const Layout> decodePreparedText(const uint8_t* bytes,size_t length);
}
