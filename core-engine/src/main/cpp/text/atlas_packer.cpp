#include "atlas_packer.h"
#include <algorithm>
#include <stdexcept>
#include <tuple>
#include <set>

namespace fluxx::text {
PackedAtlas packGlyphs(std::vector<std::shared_ptr<const SdfGlyph>> glyphs, int size) {
    if (size != 1024 && size != 2048) throw std::invalid_argument("Unsupported atlas size");
    for (const auto& glyph : glyphs) if (!glyph) throw std::invalid_argument("Null glyph lease");
    std::sort(glyphs.begin(), glyphs.end(), [](const auto& a, const auto& b) {
        return std::make_tuple(-a->height, -a->width, a->fontId, a->glyphId) <
               std::make_tuple(-b->height, -b->width, b->fontId, b->glyphId);
    });
    PackedAtlas atlas{};
    atlas.size = size; atlas.requested = glyphs.size();
    atlas.pixels.resize(static_cast<size_t>(size) * size, 0);
    struct Shelf { int x, y, height; };
    std::vector<Shelf> shelves;
    int nextY = 0;
    for (const auto& glyph : glyphs) {
        if (!glyph->width || !glyph->height) { ++atlas.empty; continue; }
        const int w = glyph->width + 2, h = glyph->height + 2;
        if (w > size || h > size) { ++atlas.rejected; continue; }
        Shelf* selected = nullptr;
        for (auto& shelf : shelves) {
            if (h <= shelf.height && shelf.x + w <= size && (!selected || shelf.x > selected->x)) selected = &shelf;
        }
        if (!selected && nextY + h <= size) {
            shelves.push_back({0, nextY, h});
            nextY += h; selected = &shelves.back();
        }
        if (!selected) { ++atlas.rejected; continue; }
        const int x = selected->x + 1, y = selected->y + 1;
        selected->x += w;
        atlas.usedPixels += static_cast<size_t>(w) * h;
        atlas.placements.push_back({glyph, x, y});
        for (int row = 0; row < glyph->height; ++row) {
            std::copy_n(glyph->pixels.data() + static_cast<size_t>(row) * glyph->width, glyph->width,
                        atlas.pixels.data() + static_cast<size_t>(y + row) * size + x);
        }
    }
    return atlas;
}
PagedAtlas packGlyphPages(std::vector<std::shared_ptr<const SdfGlyph>> glyphs, unsigned maxPages) {
    // TEXT FIX 9d: TEXT UI 6 caps the atlas at 6 pages (24 MiB; 3 on low-RAM devices) and the JNI accepts 1..8; the E2
    // bound of 4 made the renderer throw when growing past 4 pages (seen on the A16 in the per-character F2 fixture).
    if(maxPages<1 || maxPages>MaxAtlasPages) throw std::invalid_argument("Invalid atlas page budget");
    PagedAtlas result;
    for(unsigned page=0;page<maxPages && !glyphs.empty();++page) {
        auto packed=packGlyphs(glyphs,2048);
        // TEXT FIX 9d: identity is the bitmap itself. Keying by (font, glyph) dropped a glyph's other tiers (48 placed on
        // one page silently discarded its unplaced 192/384 copy) once packing spilled onto a second page.
        std::set<const SdfGlyph*> placed;
        for(const auto& entry:packed.placements) placed.insert(entry.glyph.get());
        glyphs.erase(std::remove_if(glyphs.begin(),glyphs.end(),[&](const auto& glyph) {
            return !glyph->width || !glyph->height || placed.count(glyph.get());
        }),glyphs.end());
        result.pages.push_back(std::move(packed));
    }
    result.rejected=glyphs.size(); return result;
}
}
