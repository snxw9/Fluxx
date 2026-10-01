#pragma once
#include "font_manager.h"

namespace fluxx::text {
struct Placement { std::shared_ptr<const SdfGlyph> glyph; int x, y; };
struct PackedAtlas {
    int size;
    size_t requested = 0, empty = 0, rejected = 0, usedPixels = 0;
    std::vector<uint8_t> pixels;
    std::vector<Placement> placements;
};
// CPU-only deterministic height/width/key sorted best-fit shelf packing, one zero guard texel.
// No FontManager access: repacking retained leases cannot acquire the font lock or rasterize.
PackedAtlas packGlyphs(std::vector<std::shared_ptr<const SdfGlyph>> glyphs, int size);
struct PagedAtlas { std::vector<PackedAtlas> pages; size_t rejected=0; };
// One guard texel per glyph. Page allocation never changes glyph/draw ordering.
PagedAtlas packGlyphPages(std::vector<std::shared_ptr<const SdfGlyph>> glyphs, unsigned maxPages);
}
