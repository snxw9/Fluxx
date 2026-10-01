#include "font_manager.h"
#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_MODULE_H
#include <hb.h>
#include <hb-ot.h>
#include <algorithm>
#include <chrono>
#include <list>
#include <mutex>
#include <set>
#include <sstream>
#include <stdexcept>
#include <unordered_map>

namespace fluxx::text {
namespace {
void ftCheck(FT_Error error, const char* operation) {
    if (error) throw std::runtime_error(std::string(operation) + ": FreeType error " + std::to_string(error));
}
void validateUtf8(const std::string& s) {
    if (s.size() > FontManager::MaxTextBytes) throw std::invalid_argument("Text exceeds 16384 UTF-8 bytes");
    for (size_t i = 0; i < s.size();) {
        uint32_t c = static_cast<uint8_t>(s[i++]);
        unsigned extra;
        uint32_t minimum;
        if (c < 128) { if (c == '\r' || c == 0) throw std::invalid_argument("Use LF newlines; NUL is unsupported"); continue; }
        if (c >= 0xc2 && c <= 0xdf) { extra = 1; minimum = 0x80; c &= 0x1f; }
        else if (c >= 0xe0 && c <= 0xef) { extra = 2; minimum = 0x800; c &= 0xf; }
        else if (c >= 0xf0 && c <= 0xf4) { extra = 3; minimum = 0x10000; c &= 7; }
        else throw std::invalid_argument("Invalid UTF-8 lead byte");
        while (extra--) {
            if (i == s.size() || (static_cast<uint8_t>(s[i]) & 0xc0) != 0x80) throw std::invalid_argument("Invalid UTF-8 continuation");
            c = (c << 6) | (static_cast<uint8_t>(s[i++]) & 0x3f);
        }
        if (c < minimum || c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff)) throw std::invalid_argument("Invalid Unicode scalar");
    }
}
struct Face {
    // Destruction is explicit: handles first, backing bytes last.
    FontInput input;
    FT_Face ft = nullptr;
    hb_blob_t* blob = nullptr;
    hb_face_t* face = nullptr;
    hb_font_t* font = nullptr;
    ~Face() {
        if (font) hb_font_destroy(font);
        if (face) hb_face_destroy(face);
        if (blob) hb_blob_destroy(blob);
        if (ft) FT_Done_Face(ft);
    }
};
} // namespace

struct FontManager::Impl {
    FT_Library library = nullptr;
    std::mutex mutex;
    std::vector<std::unique_ptr<Face>> faces;
    struct RasterEntry { std::string key; std::shared_ptr<const SdfGlyph> value; };
    std::list<RasterEntry> rasters;
    std::unordered_map<std::string, std::list<RasterEntry>::iterator> index;
    struct LayoutEntry { std::string key; std::shared_ptr<const Layout> value; };
    std::list<LayoutEntry> layouts;
    size_t budget;
    uint64_t rasterizations = 0, hits = 0, waitNs = 0;
    explicit Impl(size_t bytes) : budget(bytes) {}
    ~Impl() { faces.clear(); if (library) FT_Done_FreeType(library); }
    std::unique_lock<std::mutex> lock() {
        const auto start = std::chrono::steady_clock::now();
        std::unique_lock<std::mutex> guard(mutex);
        waitNs += static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - start).count());
        return guard;
    }
    Face& resolve(const std::string& id) {
        for (auto& face : faces) if (face->input.id == id) return *face;
        for (auto& face : faces) if (face->input.id == "fluxx.sans") return *face;
        throw std::runtime_error("Default font absent");
    }
    void evict() {
        size_t unpinned = 0;
        for (const auto& entry : rasters) if (entry.value.use_count() == 1) unpinned += entry.value->pixels.size();
        for (auto entry = rasters.begin(); entry != rasters.end() && unpinned > budget;) {
            if (entry->value.use_count() == 1) {
                unpinned -= entry->value->pixels.size();
                index.erase(entry->key);
                entry = rasters.erase(entry);
            } else ++entry;
        }
        // Also bound metadata for empty-outline glyphs. Pinned entries are never revoked.
        for (auto entry = rasters.begin(); entry != rasters.end() && rasters.size() > 8192;) {
            if (entry->value.use_count() == 1) { index.erase(entry->key); entry = rasters.erase(entry); }
            else ++entry;
        }
    }
};

FontManager::FontManager(std::vector<FontInput> fonts, size_t budget) : impl_(std::make_unique<Impl>(budget)) {
    ftCheck(FT_Init_FreeType(&impl_->library), "FT_Init_FreeType");
    FT_Int major = 0, minor = 0, patch = 0;
    FT_Library_Version(impl_->library, &major, &minor, &patch);
    if (major != 2 || minor != 14 || patch != 1 || std::string(hb_version_string()) != "14.3.1")
        throw std::runtime_error("Unexpected native font library version");
    unsigned spread = Spread;
    ftCheck(FT_Property_Set(impl_->library, "sdf", "spread", &spread), "Set SDF spread");
    // Check both overlap modes explicitly; unsupported properties must not silently pass E1c.
    for (FT_Bool enabled : {FT_Bool(0), FT_Bool(1)}) {
        ftCheck(FT_Property_Set(impl_->library, "sdf", "overlaps", &enabled), "Set SDF overlaps");
        // In pinned FreeType 2.14.1 the setter reads FT_Bool, but the getter writes FT_Int.
        FT_Int actual = 0;
        ftCheck(FT_Property_Get(impl_->library, "sdf", "overlaps", &actual), "Get SDF overlaps");
        if (actual != enabled) throw std::runtime_error("SDF overlaps readback mismatch");
    }
    std::set<std::string> ids;
    for (auto& input : fonts) {
        if (!ids.insert(input.id).second || input.bytes.empty() || input.bytes.size() > 16 * 1024 * 1024) throw std::invalid_argument("Invalid font input");
        auto face = std::make_unique<Face>();
        face->input = std::move(input);
        ftCheck(FT_New_Memory_Face(impl_->library, face->input.bytes.data(), static_cast<FT_Long>(face->input.bytes.size()), 0, &face->ft), "FT_New_Memory_Face");
        if (FT_HAS_MULTIPLE_MASTERS(face->ft) || !FT_IS_SCALABLE(face->ft) ||
            (face->ft->style_flags & (FT_STYLE_FLAG_BOLD | FT_STYLE_FLAG_ITALIC))) throw std::invalid_argument("Expected static scalable Regular face");
        ftCheck(FT_Select_Charmap(face->ft, FT_ENCODING_UNICODE), "Unicode charmap");
        face->blob = hb_blob_create(reinterpret_cast<const char*>(face->input.bytes.data()), static_cast<unsigned>(face->input.bytes.size()), HB_MEMORY_MODE_READONLY, nullptr, nullptr);
        face->face = hb_face_create(face->blob, 0);
        face->font = hb_font_create(face->face);
        hb_ot_font_set_funcs(face->font);
        const unsigned upem = hb_face_get_upem(face->face);
        if (!upem || upem != face->ft->units_per_EM) throw std::runtime_error("Font metric disagreement");
        hb_font_set_scale(face->font, static_cast<int>(upem), static_cast<int>(upem));
        hb_font_make_immutable(face->font);
        impl_->faces.push_back(std::move(face));
    }
    if (!ids.count("fluxx.sans")) throw std::invalid_argument("Default font required");
}
FontManager::~FontManager() = default;

std::shared_ptr<const Layout> FontManager::shape(const std::string& id, const std::string& text) {
    validateUtf8(text);
    auto guard = impl_->lock();
    auto& face = impl_->resolve(id);
    const std::string key = face.input.id + '\0' + text;
    for (auto entry = impl_->layouts.begin(); entry != impl_->layouts.end(); ++entry) {
        if (entry->key == key) {
            auto value = entry->value;
            impl_->layouts.splice(impl_->layouts.end(), impl_->layouts, entry);
            return value;
        }
    }
    auto result = std::make_shared<Layout>();
    result->fontId = face.input.id;
    result->upem = hb_face_get_upem(face.face);
    hb_font_extents_t extents{};
    if (!hb_font_get_h_extents(face.font, &extents)) throw std::runtime_error("Missing horizontal metrics");
    result->ascent = extents.ascender; result->descent = extents.descender; result->lineGap = extents.line_gap;
    size_t start = 0;
    // Empty string has no lines, glyphs or bounds; explicit empty lines retain vertical metrics.
    while (!text.empty()) {
        const size_t end = text.find('\n', start);
        const size_t length = (end == std::string::npos ? text.size() : end) - start;
        std::unique_ptr<hb_buffer_t, decltype(&hb_buffer_destroy)> buffer(hb_buffer_create(), hb_buffer_destroy);
        hb_buffer_set_direction(buffer.get(), HB_DIRECTION_LTR);
        hb_buffer_set_script(buffer.get(), HB_SCRIPT_LATIN);
        hb_buffer_set_language(buffer.get(), hb_language_from_string("en", -1));
        hb_buffer_set_cluster_level(buffer.get(), HB_BUFFER_CLUSTER_LEVEL_MONOTONE_GRAPHEMES);
        // Feed each line independently, then restore global UTF-8 byte clusters.
        hb_buffer_add_utf8(buffer.get(), text.data() + start, static_cast<int>(length), 0, static_cast<int>(length));
        hb_shape(face.font, buffer.get(), nullptr, 0);
        if (!hb_buffer_allocation_successful(buffer.get())) throw std::bad_alloc();
        unsigned count = 0;
        const auto* info = hb_buffer_get_glyph_infos(buffer.get(), &count);
        const auto* pos = hb_buffer_get_glyph_positions(buffer.get(), nullptr);
        int64_t advance = 0;
        for (unsigned i = 0; i < count; ++i) {
            hb_glyph_extents_t ink{};
            hb_font_get_glyph_extents(face.font, info[i].codepoint, &ink);
            result->glyphs.push_back({info[i].codepoint, info[i].cluster + static_cast<uint32_t>(start),
                static_cast<uint32_t>(result->lineAdvances.size()), pos[i].x_advance, pos[i].y_advance, pos[i].x_offset, pos[i].y_offset,
                ink.x_bearing, ink.y_bearing, ink.width, ink.height});
            advance += pos[i].x_advance;
        }
        result->lineAdvances.push_back(advance);
        if (end == std::string::npos) break;
        start = end + 1;
    }
    impl_->layouts.push_back({key, result});
    // 64 bounded layouts, each at most MaxTextBytes input. Leases survive eviction.
    if (impl_->layouts.size() > 64) impl_->layouts.pop_front();
    return result;
}

std::shared_ptr<const SdfGlyph> FontManager::sdf(const std::string& id, uint32_t glyphId, bool overlaps) {
    auto guard = impl_->lock();
    auto& face = impl_->resolve(id);
    if (glyphId >= static_cast<uint32_t>(face.ft->num_glyphs)) throw std::out_of_range("Invalid glyph ID");
    // Font catalog is immutable for this manager; resolved ID identifies the exact owned bytes.
    const std::string key = face.input.id + ':' + std::to_string(glyphId) + (overlaps ? ":48:8:1" : ":48:8:0");
    impl_->evict();
    auto found = impl_->index.find(key);
    if (found != impl_->index.end()) {
        ++impl_->hits;
        impl_->rasters.splice(impl_->rasters.end(), impl_->rasters, found->second);
        return found->second->value;
    }
    FT_Bool enabled = overlaps ? 1 : 0;
    ftCheck(FT_Property_Set(impl_->library, "sdf", "overlaps", &enabled), "Set SDF overlaps");
    ftCheck(FT_Set_Pixel_Sizes(face.ft, 0, Ppem), "FT_Set_Pixel_Sizes");
    ftCheck(FT_Load_Glyph(face.ft, glyphId, FT_LOAD_NO_HINTING | FT_LOAD_NO_BITMAP), "FT_Load_Glyph");
    auto result = std::make_shared<SdfGlyph>();
    result->fontId = face.input.id; result->glyphId = glyphId; result->overlaps = overlaps;
    if (face.ft->glyph->outline.n_contours != 0) {
        ftCheck(FT_Render_Glyph(face.ft->glyph, FT_RENDER_MODE_SDF), "FT_Render_Glyph SDF");
        ++impl_->rasterizations;
        const auto& bitmap = face.ft->glyph->bitmap;
        if (bitmap.pixel_mode != FT_PIXEL_MODE_GRAY) throw std::runtime_error("Expected R8 SDF");
        result->width = static_cast<int>(bitmap.width); result->height = static_cast<int>(bitmap.rows);
        result->left = face.ft->glyph->bitmap_left; result->top = face.ft->glyph->bitmap_top;
        result->pixels.resize(static_cast<size_t>(result->width) * result->height);
        for (int y = 0; y < result->height; ++y) {
            const auto* row = bitmap.pitch >= 0 ? bitmap.buffer + y * bitmap.pitch : bitmap.buffer + (result->height - 1 - y) * -bitmap.pitch;
            std::copy_n(row, result->width, result->pixels.data() + static_cast<size_t>(y) * result->width);
        }
    }
    impl_->rasters.push_back({key, result});
    impl_->index[key] = std::prev(impl_->rasters.end());
    impl_->evict();
    return result;
}

uint32_t FontManager::glyphForCodepoint(const std::string& id, uint32_t codepoint) {
    auto guard = impl_->lock();
    return FT_Get_Char_Index(impl_->resolve(id).ft, codepoint);
}
std::vector<uint32_t> FontManager::latinGlyphs(const std::string& id) {
    auto guard = impl_->lock();
    auto& face = impl_->resolve(id);
    std::set<uint32_t> glyphs{0};
    for (uint32_t cp = 0x20; cp <= 0x206f; ++cp) {
        if (cp > 0x24f && cp < 0x1e00) continue;
        if (cp > 0x1eff && cp < 0x2000) continue;
        const auto gid = FT_Get_Char_Index(face.ft, cp);
        if (gid) glyphs.insert(gid);
    }
    return {glyphs.begin(), glyphs.end()};
}
FontStats FontManager::stats() {
    auto guard = impl_->lock();
    FontStats result{impl_->rasterizations, impl_->hits, impl_->waitNs, 0, 0, impl_->layouts.size()};
    for (const auto& entry : impl_->rasters) {
        result.cacheBytes += entry.value->pixels.size();
        if (entry.value.use_count() > 1) result.pinnedBytes += entry.value->pixels.size();
    }
    return result;
}
void FontManager::trim() { auto guard = impl_->lock(); impl_->evict(); }

std::vector<double> layoutMetrics(const Layout& layout) {
    std::vector<double> result{static_cast<double>(layout.upem), static_cast<double>(layout.ascent),
        static_cast<double>(layout.descent), static_cast<double>(layout.lineGap),
        static_cast<double>(layout.lineAdvances.size()), static_cast<double>(layout.glyphs.size())};
    for(auto advance:layout.lineAdvances) result.push_back(static_cast<double>(advance));
    int64_t pen=0; uint32_t line=0;
    for(const auto& glyph:layout.glyphs) {
        if(glyph.line!=line) { line=glyph.line; pen=0; }
        for(double value:{static_cast<double>(line),static_cast<double>(pen+glyph.xOffset),
            static_cast<double>(-glyph.yOffset),static_cast<double>(glyph.xBearing),
            static_cast<double>(glyph.yBearing),static_cast<double>(glyph.inkWidth),static_cast<double>(glyph.inkHeight)}) result.push_back(value);
        pen+=glyph.xAdvance;
    }
    return result;
}
std::string layoutJson(const Layout& layout) {
    std::ostringstream out;
    out << "{\"font\":\"" << layout.fontId << "\",\"upem\":" << layout.upem
        << ",\"ascent\":" << layout.ascent << ",\"descent\":" << layout.descent << ",\"lineGap\":" << layout.lineGap << ",\"lines\":[";
    for (size_t i = 0; i < layout.lineAdvances.size(); ++i) { if (i) out << ','; out << layout.lineAdvances[i]; }
    out << "],\"glyphs\":[";
    for (size_t i = 0; i < layout.glyphs.size(); ++i) {
        const auto& g = layout.glyphs[i];
        if (i) out << ',';
        out << "{\"g\":" << g.id << ",\"cl\":" << g.cluster << ",\"line\":" << g.line
            << ",\"ax\":" << g.xAdvance << ",\"ay\":" << g.yAdvance << ",\"dx\":" << g.xOffset << ",\"dy\":" << g.yOffset << '}';
    }
    out << "]}";
    return out.str();
}
} // namespace fluxx::text
