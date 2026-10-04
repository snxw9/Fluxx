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
struct SdfGlyph;
struct Layout {
    std::string fontId;
    uint32_t upem;
    int32_t ascent, descent, lineGap;
    std::vector<int64_t> lineAdvances;
    std::vector<Glyph> glyphs;
    // Shared F1/F2 membership, prepared once with shaping (LTR Latin only).
    struct Character { uint32_t byteStart, byteEnd, line, word, nonSpaceCharacter; bool space; };
    struct Cluster { uint32_t byteStart, byteEnd, line, word, character, nonSpaceCharacter; bool space;
        uint32_t characterEnd=0, nonSpaceCharacterEnd=0; };
    std::vector<Character> characters;
    std::vector<Cluster> clusters;
    std::vector<uint32_t> glyphClusters, lineClusterCounts;
    struct Geometry {
        uint32_t fontIndex=0;
        double advance=0,xOffset=0,yOffset=0,xBearing=0,yBearing=0,inkWidth=0,inkHeight=0;
        double scaleX=1,scaleY=1,fontSize=1,baseline=0,shear=0,bold=0;
    };
    // Optional styled geometry uses the layout's common design units; legacy layouts stay unchanged.
    std::vector<std::string> fontIds;
    bool styled=false;
    std::vector<Geometry> geometry;
    // Imported fonts are parsed/shaped/rasterized in an isolated process. The renderer owns
    // validated immutable results, never their untrusted font bytes or parser handles.
    std::vector<std::shared_ptr<const SdfGlyph>> externalSdfs;
    std::vector<double> styledLineAdvances;
    double styledAscent=0,styledDescent=0,styledLineGap=0;
    const std::string& glyphFont(size_t i) const { return geometry.empty()?fontId:fontIds[geometry[i].fontIndex]; }
    double lineAdvance(size_t i) const { return styledLineAdvances.empty()?static_cast<double>(lineAdvances[i]):styledLineAdvances[i]; }
};
struct StyledRun {
    uint32_t start=0,end=0;
    std::string fontId;
    double size=1,horizontal=1,vertical=1;
    unsigned caps=0,script=0;
    bool kerning=true,fauxBold=false,fauxItalic=false;
};
struct StyledInput { std::string text;std::vector<StyledRun> runs;std::vector<uint32_t> uppercase; };
StyledInput decodeStyledInput(const uint8_t* bytes,size_t length);
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
    uint64_t shapes=0;
};

/** CPU only. All face mutation is serialized; immutable results outlive the manager safely.
 * Font bytes outlive FT_Face and HB blob/font. Never acquire this mutex on the UI thread.
 * Shared SDF leases pin pixels for prepare/repack; repack consumes leases without this manager.
 */
class FontManager {
public:
    static constexpr unsigned Ppem = 48, Spread = 8;
    static constexpr size_t MaxTextBytes = 16384;
    explicit FontManager(std::vector<FontInput> fonts, size_t unpinnedBudget = 8 * 1024 * 1024,
                         unsigned ppem = Ppem, unsigned spread = Spread);
    ~FontManager();
    FontManager(const FontManager&) = delete;
    FontManager& operator=(const FontManager&) = delete;
    std::shared_ptr<const Layout> shape(const std::string& fontId, const std::string& utf8, bool ligatures=true,
                                      bool kerning=true,unsigned features=0);
    std::shared_ptr<const Layout> shapeStyled(const std::string& fontId,const std::string& utf8,
        const std::vector<StyledRun>& runs,const std::vector<uint32_t>& uppercase,bool ligatures=true);
    bool hasFeature(const std::string& fontId,uint32_t tag);
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
