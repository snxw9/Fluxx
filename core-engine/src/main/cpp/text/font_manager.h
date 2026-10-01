#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace fluxx::text {

struct FontInput { std::string id; std::vector<uint8_t> bytes; };
struct Glyph {
    uint32_t id, cluster, line;
    int32_t xAdvance, yAdvance, xOffset, yOffset;
    int32_t xBearing = 0, yBearing = 0, inkWidth = 0, inkHeight = 0;
};
struct Layout {
    std::string fontId;
    uint32_t upem;
    int32_t ascent, descent, lineGap;
    std::vector<int64_t> lineAdvances;
    std::vector<Glyph> glyphs;
};
struct SdfGlyph {
    std::string fontId;
    uint32_t glyphId;
    bool overlaps;
    int width = 0, height = 0, left = 0, top = 0;
    // Immutable owned R8 pixels, top-to-bottom; bearings include SDF spread.
    std::vector<uint8_t> pixels;
};
struct FontStats {
    uint64_t rasterizations, cacheHits, lockWaitNs;
    size_t cacheBytes, pinnedBytes, layouts;
};

/** CPU only. All face mutation is serialized; immutable results outlive the manager safely.
 * Font bytes outlive FT_Face and HB blob/font. Never acquire this mutex on the UI thread.
 * Shared SDF leases pin pixels for prepare/repack; repack consumes leases without this manager.
 */
class FontManager {
public:
    static constexpr unsigned Ppem = 48, Spread = 8;
    static constexpr size_t MaxTextBytes = 16384;
    explicit FontManager(std::vector<FontInput> fonts, size_t unpinnedBudget = 8 * 1024 * 1024);
    ~FontManager();
    FontManager(const FontManager&) = delete;
    FontManager& operator=(const FontManager&) = delete;
    std::shared_ptr<const Layout> shape(const std::string& fontId, const std::string& utf8);
    std::shared_ptr<const SdfGlyph> sdf(const std::string& fontId, uint32_t glyphId, bool overlaps = true);
    std::vector<uint32_t> latinGlyphs(const std::string& fontId);
    uint32_t glyphForCodepoint(const std::string& fontId, uint32_t codepoint);
    FontStats stats();
    void trim();
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

std::string layoutJson(const Layout& layout);
std::vector<double> layoutMetrics(const Layout& layout);
} // namespace fluxx::text
