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
            const auto clustered=manager.shape("fluxx.serif","office A\xcc\x81 B\n\nC");
            require(clustered->glyphClusters.size()==clustered->glyphs.size(),"Missing glyph membership");
            require(clustered->lineClusterCounts.size()==3 && clustered->lineClusterCounts[1]==0,"Empty line membership lost");
            const auto shapesBefore=manager.stats().shapes;
            require(manager.shape("fluxx.serif","office A\xcc\x81 B\n\nC")==clustered,"Layout membership must be cached with shaping");
            require(manager.stats().shapes==shapesBefore,"Cached membership reshaped");
            require(clustered->characters.size()==11,"Latin combining mark must share its source character");
            bool ligatureSpan=false;
            for(size_t i=0;i<clustered->glyphs.size();++i) {
                const auto& cluster=clustered->clusters[clustered->glyphClusters[i]];
                require(cluster.byteStart==clustered->glyphs[i].cluster && cluster.byteEnd>cluster.byteStart,"Cluster byte boundary mismatch");
                require(cluster.line==clustered->glyphs[i].line,"Line membership mismatch");
                require(cluster.space==(cluster.nonSpaceCharacter==UINT32_MAX),"Space exclusion mismatch");
                require(cluster.characterEnd>cluster.character,"Empty source-character span");
                if(cluster.characterEnd-cluster.character>1) ligatureSpan=true;
            }
            require(ligatureSpan,"Ligature source-character span was lost");
            const auto unligated=manager.shape("fluxx.serif","office A\xcc\x81 B\n\nC",false);
            require(unligated->glyphs.size()>clustered->glyphs.size(),"Ligatures-off must split office");
            require(unligated->characters.size()==clustered->characters.size(),"Shaping mode changes character membership");
            require(unligated->lineClusterCounts[1]==0,"Unligated empty line lost");
            const auto modeShapes=manager.stats().shapes;
            for(int i=0;i<600;++i) {
                require(manager.shape("fluxx.serif","office A\xcc\x81 B\n\nC",true)==clustered,"Ligated cache changed");
                require(manager.shape("fluxx.serif","office A\xcc\x81 B\n\nC",false)==unligated,"Unligated cache changed");
            }
            require(manager.stats().shapes==modeShapes,"Prepared tracking zero crossings reshaped");
            for(const auto& variant:{clustered,unligated}) {
                const auto metrics=layoutMetrics(*variant);
                const size_t prefix=6+variant->lineAdvances.size()*2+variant->glyphs.size()*8;
                require(metrics[prefix]==271828,"Animator metadata marker missing");
                require(metrics.size()==prefix+4+variant->clusters.size()*6+variant->characters.size()*4+variant->glyphs.size(),"Animator metadata size mismatch");
                require(metrics[prefix+3]==4,"Word membership count mismatch");
            }
            const std::string maximum(4096,'A');
            require(manager.shape("fluxx.sans",maximum,true)->glyphs.size()==4096,"Maximum ligated fixture glyph count");
            require(manager.shape("fluxx.sans",maximum,false)->glyphs.size()==4096,"Maximum unligated fixture glyph count");
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
            std::cout << "PASS: dual ligature layouts/600 cached switches, cluster metadata, 4096-glyph modes, fallback, newline, missing glyph, UTF8, pinned LRU/lifetime, concurrent shaping/rasterization\n";
        } else throw std::runtime_error("Unknown CPU proof command");
        return 0;
    } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
