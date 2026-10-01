#include "text_diagnostics.h"
#include "atlas_packer.h"
#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_GLYPH_H
#include FT_OUTLINE_H
#include FT_MODULE_H
#include <algorithm>
#include <cmath>
#include <fstream>
#include <map>
#include <set>
#include <sstream>
#include <stdexcept>

namespace fluxx::text {
namespace {
void append32(std::vector<uint8_t>& out, uint32_t value) {
    for (int shift : {24, 16, 8, 0}) out.push_back(static_cast<uint8_t>(value >> shift));
}
void chunk(std::vector<uint8_t>& out, const std::string& type, const std::vector<uint8_t>& data) {
    append32(out, static_cast<uint32_t>(data.size()));
    const size_t start = out.size();
    out.insert(out.end(), type.begin(), type.end()); out.insert(out.end(), data.begin(), data.end());
    uint32_t crc = 0xffffffff;
    for (size_t i = start; i < out.size(); ++i) {
        crc ^= out[i];
        for (int bit = 0; bit < 8; ++bit) crc = (crc >> 1) ^ (0xedb88320u & (0u - (crc & 1u)));
    }
    append32(out, ~crc);
}
double sample(const SdfGlyph& glyph, double x, double y) {
    const int x0 = static_cast<int>(std::floor(x)), y0 = static_cast<int>(std::floor(y));
    const auto pixel = [&](int a, int b) -> double {
        if (a < 0 || b < 0 || a >= glyph.width || b >= glyph.height) return 0;
        return glyph.pixels[static_cast<size_t>(b) * glyph.width + a];
    };
    const double fx = x - x0, fy = y - y0;
    return (pixel(x0, y0) * (1 - fx) + pixel(x0 + 1, y0) * fx) * (1 - fy) +
           (pixel(x0, y0 + 1) * (1 - fx) + pixel(x0 + 1, y0 + 1) * fx) * fy;
}
void renderSample(FontManager& manager, const Layout& layout, int fontSize, bool overlaps, const std::filesystem::path& path) {
    const double units = static_cast<double>(fontSize) / layout.upem;
    const double rasterScale = static_cast<double>(fontSize) / FontManager::Ppem;
    const double step = (layout.ascent - layout.descent + layout.lineGap) * units;
    const int margin = fontSize + 12;
    const int64_t maxAdvance = layout.lineAdvances.empty() ? 0 : *std::max_element(layout.lineAdvances.begin(), layout.lineAdvances.end());
    const int width = std::max(1, static_cast<int>(std::ceil(static_cast<double>(maxAdvance) * units)) + margin * 2);
    const int height = std::max(1, static_cast<int>(std::ceil(step * static_cast<double>(layout.lineAdvances.size()))) + margin * 2);
    std::vector<uint8_t> pixels(static_cast<size_t>(width) * height);
    std::vector<int64_t> pens(layout.lineAdvances.size(), 0);
    for (const auto& glyph : layout.glyphs) {
        auto raster = manager.sdf(layout.fontId, glyph.id, overlaps);
        const double originX = margin + static_cast<double>(pens[glyph.line] + glyph.xOffset) * units + raster->left * rasterScale;
        const double originY = margin + layout.ascent * units + glyph.line * step - glyph.yOffset * units - raster->top * rasterScale;
        pens[glyph.line] += glyph.xAdvance;
        for (int y = std::max(0, static_cast<int>(std::floor(originY))); y < std::min(height, static_cast<int>(std::ceil(originY + raster->height * rasterScale))); ++y) {
            for (int x = std::max(0, static_cast<int>(std::floor(originX))); x < std::min(width, static_cast<int>(std::ceil(originX + raster->width * rasterScale))); ++x) {
                const double value = sample(*raster, (x + .5 - originX) / rasterScale - .5, (y + .5 - originY) / rasterScale - .5);
                const double distance = (value - 128.0) * FontManager::Spread / 128.0 * rasterScale;
                const auto coverage = static_cast<uint8_t>(std::lround(std::clamp(distance + .5, 0.0, 1.0) * 255));
                auto& old = pixels[static_cast<size_t>(y) * width + x];
                old = static_cast<uint8_t>(coverage + (static_cast<unsigned>(old) * (255 - coverage) + 127) / 255);
            }
        }
    }
    writeGrayPng(path, width, height, pixels);
}
void writeFile(const std::filesystem::path& path, const std::string& text) {
    std::ofstream stream(path, std::ios::binary);
    stream << text;
    if (!stream) throw std::runtime_error("Cannot write diagnostic report");
}

std::string overlapProbe(const std::filesystem::path& directory) {
    const auto checked = [](FT_Error code) { if (code) throw std::runtime_error("Overlap probe FreeType error " + std::to_string(code)); };
    struct Library { FT_Library value = nullptr; ~Library() { if (value) FT_Done_FreeType(value); } } library;
    checked(FT_Init_FreeType(&library.value));
    unsigned spread = FontManager::Spread;
    checked(FT_Property_Set(library.value, "sdf", "spread", &spread));
    int centers[2]{};
    int interiors[2]{255, 255};
    std::vector<uint8_t> previous;
    size_t changed = 0;
    for (int mode = 0; mode < 2; ++mode) {
        FT_Bool overlaps = static_cast<FT_Bool>(mode);
        checked(FT_Property_Set(library.value, "sdf", "overlaps", &overlaps));
        struct OwnedGlyph { FT_Glyph value = nullptr; ~OwnedGlyph() { if (value) FT_Done_Glyph(value); } } image;
        checked(FT_New_Glyph(library.value, FT_GLYPH_FORMAT_OUTLINE, &image.value));
        auto& outline = reinterpret_cast<FT_OutlineGlyph>(image.value)->outline;
        checked(FT_Outline_New(library.value, 8, 2, &outline));
        // Two clockwise rectangles with a 16px-wide overlap. The correct union is solid.
        const FT_Vector points[]{{0,0},{0,2048},{2048,2048},{2048,0}, {1024,0},{1024,2048},{3072,2048},{3072,0}};
        for (int i = 0; i < 8; ++i) { outline.points[i] = points[i]; outline.tags[i] = FT_CURVE_TAG_ON; }
        outline.contours[0] = 3; outline.contours[1] = 7;
        checked(FT_Outline_Check(&outline));
        checked(FT_Glyph_To_Bitmap(&image.value, FT_RENDER_MODE_SDF, nullptr, 1));
        const auto bitmapGlyph = reinterpret_cast<FT_BitmapGlyph>(image.value);
        const auto& bitmap = bitmapGlyph->bitmap;
        const int width = static_cast<int>(bitmap.width), height = static_cast<int>(bitmap.rows);
        std::vector<uint8_t> pixels(static_cast<size_t>(width) * height);
        for (int y = 0; y < height; ++y) std::copy_n(bitmap.buffer + y * bitmap.pitch, width, pixels.data() + static_cast<size_t>(y) * width);
        const int x = 24 - bitmapGlyph->left, y = bitmapGlyph->top - 16;
        if (x < 0 || x >= width || y < 0 || y >= height) throw std::runtime_error("Overlap probe bounds invalid");
        centers[mode] = pixels[static_cast<size_t>(y) * width + x];
        for (int py = 8; py < 24; ++py) for (int px = 8; px < 40; ++px) {
            const auto value = pixels[static_cast<size_t>(bitmapGlyph->top - py) * width + px - bitmapGlyph->left];
            interiors[mode] = std::min(interiors[mode], static_cast<int>(value));
        }
        writeGrayPng(directory / (mode ? "synthetic-overlap-on.png" : "synthetic-overlap-off.png"), width, height, pixels);
        if (mode && pixels.size() == previous.size()) {
            for (size_t i = 0; i < pixels.size(); ++i) if (pixels[i] != previous[i]) ++changed;
        }
        previous = std::move(pixels);
    }
    if (centers[1] <= 128 || interiors[1] < 240) throw std::runtime_error("Overlap-aware SDF has a false interior edge in the union");
    return "{\"overlapOffCenter\":" + std::to_string(centers[0]) + ",\"overlapOnCenter\":" + std::to_string(centers[1]) +
           ",\"overlapOffInteriorMin\":" + std::to_string(interiors[0]) + ",\"overlapOnInteriorMin\":" + std::to_string(interiors[1]) +
           ",\"changedPixels\":" + std::to_string(changed) + ",\"unionInteriorPass\":true}";
}
} // namespace

// Minimal standards-compliant grayscale PNG writer: stored DEFLATE blocks, CRC32 and Adler32.
// Debug only; no extra native PNG dependency or impact on product rendering.
void writeGrayPng(const std::filesystem::path& path, int width, int height, const std::vector<uint8_t>& pixels) {
    if (width <= 0 || height <= 0 || pixels.size() != static_cast<size_t>(width) * height) throw std::invalid_argument("Invalid PNG dimensions");
    std::vector<uint8_t> raw;
    for (int y = 0; y < height; ++y) {
        raw.push_back(0);
        raw.insert(raw.end(), pixels.begin() + static_cast<size_t>(y) * width, pixels.begin() + static_cast<size_t>(y + 1) * width);
    }
    std::vector<uint8_t> zlib{0x78, 0x01};
    uint32_t a = 1, b = 0;
    for (uint8_t value : raw) { a = (a + value) % 65521; b = (b + a) % 65521; }
    for (size_t at = 0; at < raw.size();) {
        const size_t count = std::min<size_t>(65535, raw.size() - at);
        const auto n = static_cast<uint16_t>(count);
        zlib.push_back(at + count == raw.size() ? 1 : 0);
        zlib.push_back(static_cast<uint8_t>(n)); zlib.push_back(static_cast<uint8_t>(n >> 8));
        zlib.push_back(static_cast<uint8_t>(~n)); zlib.push_back(static_cast<uint8_t>(~n >> 8));
        zlib.insert(zlib.end(), raw.begin() + at, raw.begin() + at + count); at += count;
    }
    append32(zlib, (b << 16) | a);
    std::vector<uint8_t> png{137, 80, 78, 71, 13, 10, 26, 10}, header;
    append32(header, static_cast<uint32_t>(width)); append32(header, static_cast<uint32_t>(height));
    header.insert(header.end(), {8, 0, 0, 0, 0});
    chunk(png, "IHDR", header); chunk(png, "IDAT", zlib); chunk(png, "IEND", {});
    std::ofstream stream(path, std::ios::binary);
    stream.write(reinterpret_cast<const char*>(png.data()), static_cast<std::streamsize>(png.size()));
    if (!stream) throw std::runtime_error("Cannot write PNG");
}

void dumpDiagnostics(FontManager& manager, const std::filesystem::path& directory) {
    std::filesystem::create_directories(directory / "glyphs");
    std::filesystem::create_directories(directory / "samples");
    struct Fixture { const char* name; const char* font; const char* text; };
    const std::vector<Fixture> fixtures{
        {"kerning", "fluxx.sans", "AV"}, {"ligature", "fluxx.serif", "office"},
        {"newline", "fluxx.serif", "office\nAV gy"}, {"missing", "fluxx.sans", "A\xf4\x8f\xbf\xbfV"},
        {"contours", "fluxx.sans", "\xc3\x85\xc3\xa9\xc3\xa6\xc3\xb8 ffi @&"},
        {"mono", "fluxx.mono", "AV office 0123456789"}};
    std::map<std::string, std::shared_ptr<const SdfGlyph>> unique;
    std::ostringstream report;
    report << "{\"ppem\":48,\"spread\":8,\"guard\":1,\"overlaps\":true,\"overlapProbe\":"
           << overlapProbe(directory) << ",\"fixtureLayouts\":[";
    bool comma = false;
    for (const auto& fixture : fixtures) {
        auto layout = manager.shape(fixture.font, fixture.text);
        if (comma) report << ',';
        comma = true;
        report << layoutJson(*layout);
        for (int size : {8, 12, 14, 16, 72}) for (bool overlaps : {false, true}) {
            renderSample(manager, *layout, size, overlaps, directory / "samples" /
                (std::string(fixture.name) + "-" + std::to_string(size) + (overlaps ? "-overlaps.png" : "-plain.png")));
        }
        for (const auto& glyph : layout->glyphs) for (bool overlaps : {false, true}) {
            auto raster = manager.sdf(fixture.font, glyph.id, overlaps);
            const std::string key = raster->fontId + '-' + std::to_string(glyph.id);
            if (overlaps) unique[key] = raster;
            if (raster->width) writeGrayPng(directory / "glyphs" / (key + (overlaps ? "-overlaps.png" : "-plain.png")), raster->width, raster->height, raster->pixels);
        }
    }
    std::vector<std::shared_ptr<const SdfGlyph>> fixtureGlyphs;
    for (const auto& entry : unique) fixtureGlyphs.push_back(entry.second);
    auto fixtureAtlas = packGlyphs(fixtureGlyphs, 1024);
    writeGrayPng(directory / "fixture-atlas-1024.png", 1024, 1024, fixtureAtlas.pixels);
    report << "],\"fixtureAtlasGlyphs\":" << fixtureAtlas.placements.size() << ",\"capacity\":[";
    // Measure actual unique cmap glyphs, never fill the atlas with repeated dummy rectangles.
    std::vector<std::shared_ptr<const SdfGlyph>> corpus;
    std::vector<std::string> failures;
    for (const std::string font : {"fluxx.sans", "fluxx.serif", "fluxx.mono"}) {
        for (uint32_t gid : manager.latinGlyphs(font)) {
            try { corpus.push_back(manager.sdf(font, gid)); }
            catch (const std::exception& error) { failures.push_back(font + ':' + std::to_string(gid) + ':' + error.what()); }
        }
    }
    comma = false;
    const auto before = manager.stats().rasterizations;
    for (int size : {1024, 2048}) {
        auto atlas = packGlyphs(corpus, size);
        auto repacked = packGlyphs(corpus, size);
        if (atlas.pixels != repacked.pixels || before != manager.stats().rasterizations) throw std::runtime_error("Repack rerasterized or changed pixels");
        writeGrayPng(directory / ("latin-atlas-" + std::to_string(size) + ".png"), size, size, atlas.pixels);
        if (comma) report << ',';
        comma = true;
        report << "{\"size\":" << size << ",\"requested\":" << atlas.requested << ",\"packed\":" << atlas.placements.size()
               << ",\"empty\":" << atlas.empty << ",\"rejected\":" << atlas.rejected << ",\"usedPixels\":" << atlas.usedPixels << '}';
    }
    report << "],\"faceCapacity\":[";
    comma = false;
    for (const std::string font : {"fluxx.sans", "fluxx.serif", "fluxx.mono"}) {
        std::vector<std::shared_ptr<const SdfGlyph>> face;
        for (const auto& glyph : corpus) if (glyph->fontId == font) face.push_back(glyph);
        for (int size : {1024, 2048}) {
            auto packed = packGlyphs(face, size);
            if (comma) report << ',';
            comma = true;
            report << "{\"font\":\"" << font << "\",\"size\":" << size << ",\"requested\":" << packed.requested
                   << ",\"packed\":" << packed.placements.size() << ",\"empty\":" << packed.empty << ",\"rejected\":" << packed.rejected << '}';
        }
    }
    if (before != manager.stats().rasterizations) throw std::runtime_error("Per-face packing rasterized glyphs");
    report << "],\"repackRasterizations\":" << (manager.stats().rasterizations - before) << ",\"rasterErrors\":[";
    for (size_t i = 0; i < failures.size(); ++i) { if (i) report << ','; report << '"' << failures[i] << '"'; }
    const auto stats = manager.stats();
    report << "],\"cpuCacheBytes\":" << stats.cacheBytes << ",\"pinnedBytes\":" << stats.pinnedBytes
           << ",\"rasterizations\":" << stats.rasterizations << ",\"lockWaitNs\":" << stats.lockWaitNs << "}\n";
    writeFile(directory / "report.json", report.str());
}
} // namespace fluxx::text
