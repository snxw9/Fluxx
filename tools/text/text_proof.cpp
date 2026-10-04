#include "font_manager.h"
#include "prepared_text_packet.h"
#include "font_inspection.h"
#include "text_diagnostics.h"
#include "atlas_packer.h"
#include <filesystem>
#include <fstream>
#include <iostream>
#include <thread>
#include <stdexcept>
#include <cmath>

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
        const char* families[] = {"fluxx.sans", "fluxx.serif", "fluxx.mono"};
        const char* files[] = {"Inter", "NotoSerif", "JetBrainsMono"};
        const char* styles[] = {"Bold", "Italic", "BoldItalic"};
        const char* suffixes[] = {".bold", ".italic", ".bold-italic"};
        for (unsigned family=0; family<3; ++family) for (unsigned style=0; style<3; ++style)
            inputs.push_back({std::string(families[family])+suffixes[style],
                read(root/(std::string(files[family])+"-"+styles[style]+".ttf"))});
        FontManager manager(inputs);
        const std::string command(argv[2]);
        if(command=="inspect" && argc==4) {
            const auto bytes=read(argv[3]);std::cout<<inspectFont(bytes.data(),bytes.size())<<'\n';
        } else if (command == "shape" && argc == 5) {
            const std::string hex(argv[4]);
            require(hex.size() % 2 == 0, "Invalid UTF8 hex");
            std::string text;
            for (size_t i = 0; i < hex.size(); i += 2) text.push_back(static_cast<char>(std::stoi(hex.substr(i, 2), nullptr, 16)));
            std::cout << layoutJson(*manager.shape(argv[3], text)) << '\n';
        } else if (command == "dump" && argc == 4) {
            dumpDiagnostics(manager, argv[3]); std::cout << "CPU dump complete: " << argv[3] << '\n';
        } else if (command == "selftest") {
            for(const auto& input:inputs) {
                const auto metadata=inspectFont(input.bytes.data(),input.bytes.size());
                require(metadata.find("\"version\":1")!=std::string::npos && metadata.find("\"axes\":[]")!=std::string::npos,"Static face inspection metadata missing");
            }
            bool invalidFont=false;try { const uint8_t bytes[]={0,1,2,3};inspectFont(bytes,sizeof(bytes)); } catch(const std::invalid_argument&) { invalidFont=true; }
            require(invalidFont,"Invalid font accepted by inspection");
            {
                StyledRun first;first.start=0;first.end=1;first.fontId="fluxx.sans.bold";first.size=2;first.horizontal=1.5;first.fauxBold=true;first.fauxItalic=true;
                StyledRun second;second.start=1;second.end=2;second.fontId="fluxx.serif.italic";second.script=1;
                const auto rich=manager.shapeStyled("fluxx.sans","ab",{first,second},{'A','B'});
                require(rich->glyphs.size()==2 && rich->characters.size()==2 && rich->clusters.size()==2,"Rich source membership changed");
                require(rich->glyphFont(0)==first.fontId && rich->glyphFont(1)==second.fontId,"Rich fonts were flattened");
                require(rich->geometry[0].scaleX==3 && rich->geometry[0].bold==.075 && rich->geometry[0].shear>0,"Rich affine/faux geometry missing");
                const auto regular=manager.shape(first.fontId,"a");
                require(std::abs(rich->geometry[0].advance-regular->glyphs[0].xAdvance*3000.0/regular->upem)<.0001,"Horizontal scale did not scale advance");
                const auto values=layoutMetrics(*rich);
                require(values[values.size()-4]==314159 && values[values.size()-3]==2,"Rich effective-size metadata missing");
                const auto packet=encodePreparedText(*rich,manager);
                const auto restored=decodePreparedText(packet.data(),packet.size());
                require(layoutMetrics(*restored)==values,"Prepared transport changed layout/membership values");
                require(restored->externalSdfs.size()==rich->glyphs.size() && !restored->externalSdfs[0]->pixels.empty(),"Prepared transport lost owned SDF pixels");
                for(size_t length=0;length<packet.size();length+=17) {
                    bool rejected=false;try { decodePreparedText(packet.data(),length); } catch(const std::invalid_argument&) { rejected=true; }
                    require(rejected,"Truncated prepared transport accepted");
                }
                auto unsupported=packet;unsupported[7]=2;
                bool rejected=false;try { decodePreparedText(unsupported.data(),unsupported.size()); } catch(const std::invalid_argument&) { rejected=true; }
                require(rejected,"Unsupported prepared transport accepted");
                auto excessive=packet;const auto fontCountOffset=8+4+rich->fontId.size()+16+24;
                for(size_t i=0;i<4;++i) excessive[fontCountOffset+i]=255;
                rejected=false;try { decodePreparedText(excessive.data(),excessive.size()); } catch(const std::invalid_argument&) { rejected=true; }
                require(rejected,"Excessive prepared font count accepted");
                size_t geometryOffset=fontCountOffset+4;for(const auto& id:rich->fontIds) geometryOffset+=4+id.size();
                geometryOffset+=4+12*rich->lineAdvances.size()+4+44+4;
                auto nonfinite=packet;for(size_t i=0;i<8;++i) nonfinite[geometryOffset+i]=0;
                nonfinite[geometryOffset]=0x7f;nonfinite[geometryOffset+1]=0xf8;
                rejected=false;try { decodePreparedText(nonfinite.data(),nonfinite.size()); } catch(const std::invalid_argument&) { rejected=true; }
                require(rejected,"Nonfinite prepared geometry accepted");
                StyledRun caps;caps.start=0;caps.end=2;caps.caps=1;
                const auto upper=manager.shapeStyled("fluxx.sans","ab",{caps},{'A','B'});
                require(upper->glyphs[0].id==manager.glyphForCodepoint("fluxx.sans",'A'),"Simple uppercase was not shaped");
                require(upper->characters.size()==2,"Uppercase changed logical membership");
                if(!manager.hasFeature("fluxx.mono",0x736d6370u)) {
                    StyledRun small;small.start=0;small.end=2;small.caps=2;
                    const auto sharp=manager.shapeStyled("fluxx.mono","\xc3\x9f",{small},{0xdf});
                    require(sharp->geometry[0].fontSize==.7,"Synthetic small caps must scale lowercase sharp-s despite its unchanged simple uppercase mapping");
                }
                StyledRun kern;kern.start=0;kern.end=2;
                const auto on=manager.shapeStyled("fluxx.sans","AV",{kern},{'A','V'});kern.kerning=false;
                const auto off=manager.shapeStyled("fluxx.sans","AV",{kern},{'A','V'});
                require(on->lineAdvance(0)<off->lineAdvance(0),"Kerning switch does not reach HarfBuzz");
                kern.vertical=2;
                const auto tall=manager.shapeStyled("fluxx.sans","AV",{kern},{'A','V'});
                require(tall->styledAscent==off->styledAscent && tall->styledDescent==off->styledDescent,"Vertical scaling changed leading");
                bool malformed=false;try { const uint8_t bytes[]={0,0,0,2};decodeStyledInput(bytes,4); } catch(const std::invalid_argument&) { malformed=true; }
                require(malformed,"Unsupported styled input version accepted");
            }
            for (unsigned family=0; family<3; ++family) for (unsigned style=0; style<3; ++style) {
                const std::string id=std::string(families[family])+suffixes[style];
                const auto face=manager.shape(id,"AV office");
                require(face->fontId==id,"Bundled style silently fell back to Regular");
                require(!face->glyphs.empty(),"Bundled style produced no glyphs");
                const auto image=manager.sdf(id,face->glyphs.front().id);
                require(image->fontId==id && !image->pixels.empty(),"Bundled style SDF failed");
            }
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
