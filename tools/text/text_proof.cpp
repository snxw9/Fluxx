#include "font_manager.h"
#include "text_diagnostics.h"
#include "atlas_packer.h"
#include <filesystem>
#include <fstream>
#include <iostream>
#include <thread>
#include <stdexcept>

using namespace fluxx::text;
std::vector<uint8_t> read(const std::filesystem::path& path) {
    std::ifstream stream(path, std::ios::binary);
    if (!stream) throw std::runtime_error("Cannot read " + path.string());
    return {std::istreambuf_iterator<char>(stream), std::istreambuf_iterator<char>()};
}
void require(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }
int main(int argc, char** argv) {
    try {
        if (argc < 3) throw std::runtime_error("Usage: text-proof FONT_DIRECTORY shape FONT_ID UTF8_HEX | dump OUTPUT_DIRECTORY | selftest");
        const std::filesystem::path root(argv[1]);
        std::vector<FontInput> inputs{{"fluxx.sans", read(root / "Inter-Regular.ttf")},
            {"fluxx.serif", read(root / "NotoSerif-Regular.ttf")}, {"fluxx.mono", read(root / "JetBrainsMono-Regular.ttf")}};
        FontManager manager(inputs);
        const std::string command(argv[2]);
        if (command == "shape" && argc == 5) {
            const std::string hex(argv[4]);
            require(hex.size() % 2 == 0, "Invalid UTF8 hex");
            std::string text;
            for (size_t i = 0; i < hex.size(); i += 2) text.push_back(static_cast<char>(std::stoi(hex.substr(i, 2), nullptr, 16)));
            std::cout << layoutJson(*manager.shape(argv[3], text)) << '\n';
        } else if (command == "dump" && argc == 4) {
            dumpDiagnostics(manager, argv[3]); std::cout << "CPU dump complete: " << argv[3] << '\n';
        } else if (command == "selftest") {
            auto layout = manager.shape("fluxx.sans", "AV");
            require(manager.shape("unknown.font", "AV") == layout, "Unknown font fallback/cache mismatch");
            require(manager.shape("fluxx.sans", "")->glyphs.empty(), "Empty text draws glyphs");
            require(manager.shape("fluxx.sans", "\n")->lineAdvances.size() == 2, "Empty explicit line lost");
            auto missing = manager.shape("fluxx.sans", "\xf4\x8f\xbf\xbf");
            require(missing->glyphs.size() == 1 && missing->glyphs[0].id == 0, "Missing glyph was dropped");
            bool rejected = false;
            try { manager.shape("fluxx.sans", "\xc0\x80"); } catch (const std::invalid_argument&) { rejected = true; }
            require(rejected, "Malformed UTF8 accepted");
            std::shared_ptr<const SdfGlyph> retained;
            {
                FontManager tiny(inputs, 1);
                retained = tiny.sdf("fluxx.sans", layout->glyphs[0].id);
                for (uint32_t cp = 'B'; cp <= 'Z'; ++cp) tiny.sdf("fluxx.sans", tiny.glyphForCodepoint("fluxx.sans", cp));
                tiny.trim();
                const auto before = tiny.stats().rasterizations;
                require(tiny.sdf("fluxx.sans", layout->glyphs[0].id) == retained, "Pinned bitmap evicted");
                require(tiny.stats().rasterizations == before, "Retained glyph rerasterized");
                require(tiny.stats().cacheBytes == tiny.stats().pinnedBytes, "Unpinned budget exceeded");
            }
            require(!retained->pixels.empty(), "Pixel lease did not survive manager destruction");
            std::vector<std::thread> workers;
            for (int i = 0; i < 4; ++i) workers.emplace_back([&] {
                for (int n = 0; n < 50; ++n) {
                    require(manager.shape("fluxx.sans", "AV") == layout, "Concurrent cache mismatch");
                    manager.sdf("fluxx.sans", layout->glyphs[0].id);
                }
            });
            for (auto& worker : workers) worker.join();
            std::vector<std::shared_ptr<const SdfGlyph>> corpus;
            for(const auto* font:{"fluxx.sans","fluxx.serif","fluxx.mono"})
                for(auto glyph:manager.latinGlyphs(font)) corpus.push_back(manager.sdf(font,glyph));
            const auto rasterizations=manager.stats().rasterizations;
            require(packGlyphPages(corpus,1).rejected>0,"Corpus no longer exercises one-page overflow");
            auto pages=packGlyphPages(corpus,2);
            require(pages.rejected==0 && pages.pages.size()==2,"Three-font corpus must fit two pages");
            require(manager.stats().rasterizations==rasterizations,"Growth rerasterized retained leases");
            std::vector<std::shared_ptr<const SdfGlyph>> oversized;
            for(unsigned i=0;i<5;++i) {
                auto glyph=std::make_shared<SdfGlyph>(); glyph->fontId="synthetic"; glyph->glyphId=i;
                glyph->width=2046; glyph->height=2046; glyph->pixels.resize(2046u*2046u);
                oversized.push_back(glyph);
            }
            require(packGlyphPages(oversized,4).rejected>0,"Four-page capacity must reject before draws");
            std::cout << "PASS: fallback, newline, missing glyph, UTF8, pinned LRU/lifetime, concurrent shaping/rasterization\n";
        } else throw std::runtime_error("Unknown CPU proof command");
        return 0;
    } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
