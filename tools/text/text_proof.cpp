#include "font_manager.h"
#include "prepared_text_packet.h"
#include "font_inspection.h"
#include "paragraph_layout.h"
#include "text_diagnostics.h"
#include "atlas_packer.h"
#include "sdf_coverage.h"
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <thread>
#include <stdexcept>
#include <cmath>
#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_MULTIPLE_MASTERS_H
#include FT_OUTLINE_H
#include FT_MODULE_H
#include <hb.h>
#include <hb-ot.h>

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
        if(command=="cursivestyleproof" && argc==4) {
            const auto bytes=read(argv[3]);
            auto* blob=hb_blob_create(reinterpret_cast<const char*>(bytes.data()),static_cast<unsigned>(bytes.size()),HB_MEMORY_MODE_READONLY,nullptr,nullptr);
            auto* face=hb_face_create(blob,0);auto* font=hb_font_create(face);hb_ot_font_set_funcs(font);
            const char* source="\xd9\x84\xd8\xa7"; // lam + alef, one required ligature in this face.
            auto shape=[&](unsigned start,int length,bool optional) {
                auto* buffer=hb_buffer_create();hb_buffer_set_direction(buffer,HB_DIRECTION_RTL);
                hb_buffer_set_script(buffer,HB_SCRIPT_ARABIC);hb_buffer_set_language(buffer,hb_language_from_string("ar",-1));
                hb_buffer_set_cluster_level(buffer,HB_BUFFER_CLUSTER_LEVEL_MONOTONE_GRAPHEMES);
                // Full pre/post context is available, even for each individual style item.
                hb_buffer_add_utf8(buffer,source,4,start,length);
                const hb_feature_t off[]={{HB_TAG('l','i','g','a'),0,0,UINT32_MAX},{HB_TAG('c','l','i','g'),0,0,UINT32_MAX}};
                hb_shape(font,buffer,optional?nullptr:off,optional?0:2);
                unsigned count=0;const auto* info=hb_buffer_get_glyph_infos(buffer,&count);
                std::vector<uint32_t> result;for(unsigned i=0;i<count;++i) result.push_back(info[i].codepoint);
                hb_buffer_destroy(buffer);return result;
            };
            for(bool optional:{true,false}) {
                const auto whole=shape(0,4,optional),lam=shape(0,2,optional),alef=shape(2,2,optional);
                require(whole.size()==1 && lam.size()==1 && alef.size()==1 && whole[0] && lam[0] && alef[0],
                    "Cursive style fixture needs a covering font with required lam-alef ligation");
                require(whole[0]!=lam[0] && whole[0]!=alef[0],"Style pieces unexpectedly retain the required ligature");
                std::cout<<"PASS: optional ligatures "<<(optional?"on":"off")<<", whole lam-alef glyph "<<whole[0]
                    <<", separate style items with full context "<<lam[0]<<' '<<alef[0]
                    <<". Keeping the word on one line does not restore a mandatory glyph across a style boundary.\n";
            }
            hb_font_destroy(font);hb_face_destroy(face);hb_blob_destroy(blob);
        } else if(command=="explodeboundaryproof") {
            const auto shaped=manager.shape("fluxx.sans","A A A",false);
            require(shaped->glyphs.size()==5,"Explode boundary fixture unexpectedly ligated");
            std::vector<BreakCluster> clusters;
            for(unsigned i=0;i<5;++i) clusters.push_back({shaped->glyphs[i].xAdvance*20.0/shaped->upem,i%2==1,i%2==1});
            ParagraphFormat paragraph;paragraph.end=5;
            const double box=clusters[0].advance+clusters[1].advance+clusters[2].advance+1;
            const auto wrapped=layoutParagraphs(clusters,{paragraph},box,24);
            const auto point=layoutParagraphs(clusters,{paragraph},0,24);
            require(wrapped.lines.size()==2 && point.lines.size()==1,"Fixture did not expose lost soft wrapping");
            paragraph.alignment=ParagraphAlign::JustifyAll;
            const auto justified=layoutParagraphs(clusters,{paragraph},box*3,24);
            require(justified.lines.size()==1 && justified.lines[0].wordExpansion>0,"Fixture did not expand word spacing");
            require(justified.positions[4]-justified.positions[0]!=point.positions[4]-point.positions[0],
                "An anchor translation unexpectedly preserved justification");
            std::cout<<"PASS: unchanged A A A has "<<wrapped.lines.size()<<" wrapped lines versus "<<point.lines.size()
                <<" point-text line; justified first-to-last origin distance "<<justified.positions[4]-justified.positions[0]
                <<" differs from point-text "<<point.positions[4]-point.positions[0]
                <<". A shared anchor cannot restore either relative layout.\n";
        } else if(command=="rtlboundaryproof" && argc==4) {
            const auto bytes=read(argv[3]);
            auto* blob=hb_blob_create(reinterpret_cast<const char*>(bytes.data()),static_cast<unsigned>(bytes.size()),HB_MEMORY_MODE_READONLY,nullptr,nullptr);
            auto* face=hb_face_create(blob,0);auto* font=hb_font_create(face);hb_ot_font_set_funcs(font);
            auto shape=[&](const char* source) {
                auto* buffer=hb_buffer_create();hb_buffer_set_direction(buffer,HB_DIRECTION_RTL);
                hb_buffer_set_script(buffer,HB_SCRIPT_ARABIC);hb_buffer_set_language(buffer,hb_language_from_string("ar",-1));
                hb_buffer_add_utf8(buffer,source,-1,0,-1);hb_shape(font,buffer,nullptr,0);
                unsigned count=0;const auto* info=hb_buffer_get_glyph_infos(buffer,&count);
                const auto* positions=hb_buffer_get_glyph_positions(buffer,nullptr);
                std::vector<std::pair<uint32_t,int32_t>> result;
                for(unsigned i=0;i<count;++i) result.push_back({info[i].codepoint,positions[i].x_advance});
                hb_buffer_destroy(buffer);return result;
            };
            const auto joined=shape("\xd8\xa8\xd8\xa8\xd8\xa8"),isolated=shape("\xd8\xa8");
            require(joined.size()==3 && isolated.size()==1 && isolated[0].first!=0,"Arabic boundary fixture requires a covering outline font");
            require(joined[0]!=isolated[0] || joined[1]!=isolated[0] || joined[2]!=isolated[0],
                "Arabic emergency line boundaries unexpectedly preserved joined forms");
            std::cout<<"PASS: joined Arabic glyphs ";for(const auto& item:joined) std::cout<<item.first<<':'<<item.second<<' ';
            std::cout<<"differ from isolated line glyph "<<isolated[0].first<<':'<<isolated[0].second<<"; fixed paragraph glyphs/advances cannot be reused at emergency boundaries\n";
            hb_font_destroy(font);hb_face_destroy(face);hb_blob_destroy(blob);
        } else if(command=="importproof" && (argc==4 || argc==5)) {
            const auto bytes=read(argv[3]);
            const auto faceIndex=argc==5?static_cast<unsigned>(std::stoul(argv[4])):0u;
            require(inspectFont(bytes.data(),bytes.size()).find("\"tag\":\"wght\"")!=std::string::npos,"Variable CFF fixture has no weight axis");
            auto varied=inputs;
            varied.push_back({"import.light",bytes,faceIndex,-1,{{0x77676874u,200.f},{0x6f70737au,20.f}}});
            varied.push_back({"import.heavy",bytes,faceIndex,-1,{{0x77676874u,900.f},{0x6f70737au,20.f}}});
            varied.push_back({"import.clamped",bytes,faceIndex,-1,{{0x77676874u,1000000.f},{0x6f70737au,20.f}}});
            FontManager imported(std::move(varied));
            const auto light=imported.shape("import.light","mmm",false,false);
            const auto heavy=imported.shape("import.heavy","mmm",false,false);
            const auto clamped=imported.shape("import.clamped","mmm",false,false);
            require(light->lineAdvance(0)!=heavy->lineAdvance(0),"Variable axes did not change HB advances");
            require(heavy->lineAdvance(0)==clamped->lineAdvance(0),"Variable axes were not clamped consistently");
            const auto lowSdf=imported.sdf("import.light",light->glyphs[0].id);
            const auto highSdf=imported.sdf("import.heavy",heavy->glyphs[0].id);
            require(lowSdf->pixels!=highSdf->pixels,"Variable axes did not change FreeType outlines");
            FT_Library ft=nullptr;require(FT_Init_FreeType(&ft)==0,"Cannot open independent FreeType proof");
            FT_Face face=nullptr;require(FT_New_Memory_Face(ft,bytes.data(),static_cast<FT_Long>(bytes.size()),faceIndex,&face)==0,"Cannot open CFF proof face");
            FT_MM_Var* axes=nullptr;require(FT_Get_MM_Var(face,&axes)==0,"Cannot read independent axes");
            std::vector<FT_Fixed> coordinates(axes->num_axis);
            for(FT_UInt a=0;a<axes->num_axis;++a) coordinates[a]=axes->axis[a].tag==0x77676874u?900*65536:axes->axis[a].tag==0x6f70737au?20*65536:axes->axis[a].def;
            require(FT_Set_Var_Design_Coordinates(face,axes->num_axis,coordinates.data())==0,"Cannot set independent coordinates");
            const auto gid=FT_Get_Char_Index(face,'m');require(FT_Load_Glyph(face,gid,FT_LOAD_NO_SCALE|FT_LOAD_NO_HINTING)==0,"Cannot load CFF proof glyph");
            require(heavy->glyphs[0].xAdvance==face->glyph->metrics.horiAdvance,"HB and FreeType variable advances disagree");
            FT_Done_MM_Var(ft,axes);FT_Done_Face(face);FT_Done_FreeType(ft);
            StyledRun run;run.start=0;run.end=6;run.fontId="import.heavy";
            const auto rich=imported.shapeStyled("fluxx.sans","office",{run},{'O','F','F','I','C','E'});
            const auto packet=encodePreparedText(*rich,imported);
            require(layoutMetrics(*decodePreparedText(packet.data(),packet.size()))==layoutMetrics(*rich),"Imported CFF transport changed rich metrics");
            std::cout<<"PASS: CFF variable outline/advance changes, clamping, independent FT/HB advance agreement and prepared transport\n";
        } else if(command=="sdfregress") {
            // TEXT FIX 9b regression: (1) synthetic overlap union, (2) FreeType byte-mapping parity of the rebuild path,
            // (3) bundled fonts keep FreeType's bytes (zero rebuilds) at every tier, (4) optional font files: zero holes.
            namespace cs=fluxx::text::coverage_sdf;
            require(cs::GeneratorVersion==2,"Generator version");
            require(cs::encode(0,8)==128 && cs::encode(8,8)==255 && cs::encode(-8,8)==0 && cs::encode(4,8)==192 && cs::encode(-4,8)==64,"FreeType byte mapping");
            require(cs::supersample(55,57)==4 && cs::supersample(371,383)==2 && cs::supersample(2048,2048)==1,"Supersample bound");
            {   // Two overlapping rectangles + a crossing bar, as overlapping contours would fill (non-zero winding union).
                const int w=40,h=40;const unsigned k=4;std::vector<uint8_t> mask(w*k*h*k);
                auto fill=[&](int x0,int y0,int x1,int y1){for(int y=y0*4;y<y1*4;++y)for(int x=x0*4;x<x1*4;++x)mask[y*w*k+x]=1;};
                fill(10,10,22,30);fill(18,10,30,30);fill(8,18,32,22);
                const auto sdf=cs::fromMask(mask,w,h,k,8);
                for(int y=10;y<30;++y)for(int x=10;x<30;++x) require(sdf[y*w+x]>=128,"Overlap union has a hole");
                require(sdf[2*w+2]<128 && sdf[35*w+35]<128,"Outside reads inside");
                require(!cs::compare(sdf,mask,w,h,k).failed(),"Rebuilt SDF fails its own check");
            }
            double maxMean=0;int maxDiff=0;
            for(const char* font:{"fluxx.sans","fluxx.serif","fluxx.mono"}) for(char c:std::string("oOHnbd8")) {
                // Parity: on glyphs FreeType gets right, the coverage rebuild reproduces FreeType's bytes closely.
                FontManager probe(inputs);const auto gid=probe.glyphForCodepoint(font,static_cast<unsigned char>(c));
                const auto ft=probe.sdf(font,gid);
                FT_Library lib=nullptr;require(FT_Init_FreeType(&lib)==0,"FT");FT_Face face=nullptr;
                const auto& bytes=inputs[std::string(font)=="fluxx.serif"?1:std::string(font)=="fluxx.mono"?2:0].bytes;
                require(FT_New_Memory_Face(lib,bytes.data(),static_cast<FT_Long>(bytes.size()),0,&face)==0,"face");
                FT_Set_Pixel_Sizes(face,0,ft->ppem);require(FT_Load_Glyph(face,gid,FT_LOAD_NO_HINTING|FT_LOAD_NO_BITMAP)==0,"load");
                const unsigned k=cs::supersample(ft->width,ft->height);
                FT_Outline_Translate(&face->glyph->outline,-ft->left*64,-(ft->top-ft->height)*64);
                FT_Matrix m{static_cast<FT_Fixed>(k)<<16,0,0,static_cast<FT_Fixed>(k)<<16};FT_Outline_Transform(&face->glyph->outline,&m);
                std::vector<uint8_t> mask(static_cast<size_t>(ft->width)*k*ft->height*k);FT_Bitmap bm{};
                bm.rows=ft->height*k;bm.width=ft->width*k;bm.pitch=static_cast<int>(ft->width*k);bm.buffer=mask.data();bm.num_grays=256;bm.pixel_mode=FT_PIXEL_MODE_GRAY;
                require(FT_Outline_Get_Bitmap(lib,&face->glyph->outline,&bm)==0,"raster");for(auto& v:mask) v=v>=128;
                const auto rebuilt=cs::fromMask(mask,ft->width,ft->height,k,ft->spread);
                double sum=0;for(size_t i=0;i<rebuilt.size();++i){const int d=std::abs(int(rebuilt[i])-int(ft->pixels[i]));sum+=d;maxDiff=std::max(maxDiff,d);}
                maxMean=std::max(maxMean,sum/rebuilt.size());
                FT_Done_Face(face);FT_Done_FreeType(lib);
            }
            std::cout<<"parity: worst mean |rebuilt-FreeType| = "<<maxMean<<" / 255, worst pixel "<<maxDiff<<'\n';
            require(maxMean<=2.0,"Coverage rebuild drifts from FreeType's mapping");
            // Independent references: raw FreeType SDF bytes, and a supersampled coverage mask on the same grid.
            FT_Library refLib=nullptr;require(FT_Init_FreeType(&refLib)==0,"FT");
            struct Raw { std::vector<uint8_t> pixels; int w=0,h=0,left=0,top=0; };
            auto rawSdf=[&](FT_Face face,FT_UInt gid,unsigned ppem,unsigned spread) {
                FT_Bool on=1;FT_Property_Set(refLib,"sdf","overlaps",&on);FT_Int sp=static_cast<FT_Int>(spread);FT_Property_Set(refLib,"sdf","spread",&sp);
                FT_Set_Pixel_Sizes(face,0,ppem);Raw r;
                require(FT_Load_Glyph(face,gid,FT_LOAD_NO_HINTING|FT_LOAD_NO_BITMAP)==0,"load");
                if(face->glyph->outline.n_contours==0) return r;
                require(FT_Render_Glyph(face->glyph,FT_RENDER_MODE_SDF)==0,"sdf");const auto& b=face->glyph->bitmap;
                r.w=static_cast<int>(b.width);r.h=static_cast<int>(b.rows);r.left=face->glyph->bitmap_left;r.top=face->glyph->bitmap_top;
                r.pixels.resize(static_cast<size_t>(r.w)*r.h);for(int y=0;y<r.h;++y) std::copy_n(b.buffer+y*b.pitch,r.w,r.pixels.data()+static_cast<size_t>(y)*r.w);
                return r;
            };
            auto maskOf=[&](FT_Face face,FT_UInt gid,unsigned ppem,const Raw& r,unsigned k) {
                FT_Set_Pixel_Sizes(face,0,ppem);require(FT_Load_Glyph(face,gid,FT_LOAD_NO_HINTING|FT_LOAD_NO_BITMAP)==0,"load");
                FT_Outline_Translate(&face->glyph->outline,-r.left*64,-(r.top-r.h)*64);
                FT_Matrix m{static_cast<FT_Fixed>(k)<<16,0,0,static_cast<FT_Fixed>(k)<<16};FT_Outline_Transform(&face->glyph->outline,&m);
                std::vector<uint8_t> mask(static_cast<size_t>(r.w)*k*r.h*k);FT_Bitmap bm{};
                bm.rows=r.h*k;bm.width=r.w*k;bm.pitch=static_cast<int>(r.w*k);bm.buffer=mask.data();bm.num_grays=256;bm.pixel_mode=FT_PIXEL_MODE_GRAY;
                require(FT_Outline_Get_Bitmap(refLib,&face->glyph->outline,&bm)==0,"raster");for(auto& v:mask) v=v>=128;return mask;
            };
            // For every glyph: a raw FreeType SDF that passes is kept byte-for-byte; one that fails is rebuilt and passes.
            auto verify=[&](const std::string& id,const std::vector<uint8_t>& bytes,FontManager& manager,const std::vector<uint32_t>& glyphs,unsigned tier,size_t& kept,size_t& rebuilt) {
                FT_Face face=nullptr;require(FT_New_Memory_Face(refLib,bytes.data(),static_cast<FT_Long>(bytes.size()),0,&face)==0,"face");
                const unsigned ppem=tier?tier:48,spread=tier?(tier==96?16u:32u):8u;
                for(auto gid:glyphs) {
                    const auto got=manager.sdf(id,gid,true,tier);const auto raw=rawSdf(face,gid,ppem,spread);
                    require(got->width==raw.w && got->height==raw.h && got->left==raw.left && got->top==raw.top,"Grid changed");
                    if(raw.w==0) continue;
                    const unsigned k=cs::supersample(raw.w,raw.h);const auto mask=maskOf(face,gid,ppem,raw,k);
                    if(!cs::compare(raw.pixels,mask,raw.w,raw.h,k).failed()) {require(got->pixels==raw.pixels,"A correct FreeType SDF changed");++kept;}
                    else {require(!cs::compare(got->pixels,mask,raw.w,raw.h,k).failed(),"Rebuilt SDF still has holes");++rebuilt;}
                }
                FT_Done_Face(face);
            };
            for(unsigned tier:{0u,96u,192u,384u}) {
                FontManager bundled(inputs);size_t kept=0,rebuilt=0;
                for(const auto& in:inputs) {
                    std::vector<uint32_t> glyphs;
                    // Full Latin-1 at the base tier; a mixed sample (round, straight, overlapping, composite) at the larger tiers.
                    const std::string sample=tier?"aegoHO&@8\xC5\xE9":"";
                    if(!tier) for(uint32_t cp=32;cp<256;++cp) {const auto g=bundled.glyphForCodepoint(in.id,cp);if(g) glyphs.push_back(g);}
                    else for(unsigned char c:sample) {const auto g=bundled.glyphForCodepoint(in.id,c);if(g) glyphs.push_back(g);}
                    verify(in.id,in.bytes,bundled,glyphs,tier,kept,rebuilt);
                }
                std::cout<<"bundled tier "<<(tier?tier:48)<<": "<<kept<<" FreeType-identical, "<<rebuilt<<" rebuilt (FreeType sign errors), 0 holes\n";
            }
            for(int a=3;a<argc;++a) {
                const std::string path(argv[a]);const auto bytes=read(path);auto set=inputs;set.push_back({"import.regress",bytes});
                FontManager imported(std::move(set));
                for(unsigned tier:{0u,96u,192u,384u}) {
                    std::vector<uint32_t> glyphs;for(uint32_t cp=33;cp<127;++cp) {const auto g=imported.glyphForCodepoint("import.regress",cp);if(g) glyphs.push_back(g);}
                    size_t kept=0,rebuilt=0;const auto start=std::chrono::steady_clock::now();
                    verify("import.regress",bytes,imported,glyphs,tier,kept,rebuilt);
                    const double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
                    std::cout<<std::filesystem::path(path).filename().string().substr(0,12)<<" tier "<<(tier?tier:48)<<": "<<kept<<" identical, "<<rebuilt
                        <<" rebuilt, 0 holes ("<<ms/std::max<size_t>(glyphs.size(),1)<<" ms/glyph incl. reference)\n";
                }
            }
            FT_Done_FreeType(refLib);
            std::cout<<"PASS: coverage-correct SDF regression\n";
        } else if(command=="ftaudit" && argc>=4) {
            // Which raw FreeType SDFs fail the coverage self-check, by how much, and how long each step costs.
            namespace cs=fluxx::text::coverage_sdf;
            const std::string which(argv[3]);const unsigned ppem=argc>=5?static_cast<unsigned>(std::stoul(argv[4])):48u;
            const unsigned spread=ppem==48?8u:ppem==96?16u:32u;
            std::vector<std::pair<std::string,std::vector<uint8_t>>> fonts;
            if(which=="bundled") for(const auto& in:inputs) fonts.push_back({in.id,in.bytes}); else fonts.push_back({which,read(which)});
            FT_Library lib=nullptr;require(FT_Init_FreeType(&lib)==0,"FT");
            FT_Bool on=1;FT_Property_Set(lib,"sdf","overlaps",&on);FT_Int sp=static_cast<FT_Int>(spread);FT_Property_Set(lib,"sdf","spread",&sp);
            double sdfMs=0,checkMs=0,rebuildMs=0;size_t total=0,failed=0;
            for(const auto& [name,bytes]:fonts) {
                FT_Face face=nullptr;require(FT_New_Memory_Face(lib,bytes.data(),static_cast<FT_Long>(bytes.size()),0,&face)==0,"face");
                FT_Set_Pixel_Sizes(face,0,ppem);
                int fontFailed=0,worstHoles=0,worstSpills=0;FT_UInt worstGlyph=0;
                for(FT_ULong cp=32;cp<0x250;++cp) { const FT_UInt gid=FT_Get_Char_Index(face,cp);if(!gid) continue;
                    auto t0=std::chrono::steady_clock::now();
                    if(FT_Load_Glyph(face,gid,FT_LOAD_NO_HINTING|FT_LOAD_NO_BITMAP) || face->glyph->outline.n_contours==0) continue;
                    if(FT_Render_Glyph(face->glyph,FT_RENDER_MODE_SDF)) continue;
                    const auto& b=face->glyph->bitmap;const int w=static_cast<int>(b.width),h=static_cast<int>(b.rows),left=face->glyph->bitmap_left,top=face->glyph->bitmap_top;
                    std::vector<uint8_t> sdf(static_cast<size_t>(w)*h);for(int y=0;y<h;++y) std::copy_n(b.buffer+y*b.pitch,w,sdf.data()+static_cast<size_t>(y)*w);
                    auto t1=std::chrono::steady_clock::now();
                    FT_Load_Glyph(face,gid,FT_LOAD_NO_HINTING|FT_LOAD_NO_BITMAP);
                    const unsigned k=cs::supersample(w,h);
                    FT_Outline_Translate(&face->glyph->outline,-left*64,-(top-h)*64);
                    FT_Matrix m{static_cast<FT_Fixed>(k)<<16,0,0,static_cast<FT_Fixed>(k)<<16};FT_Outline_Transform(&face->glyph->outline,&m);
                    std::vector<uint8_t> mask(static_cast<size_t>(w)*k*h*k);FT_Bitmap bm{};
                    bm.rows=h*k;bm.width=w*k;bm.pitch=static_cast<int>(w*k);bm.buffer=mask.data();bm.num_grays=256;bm.pixel_mode=FT_PIXEL_MODE_GRAY;
                    FT_Outline_Get_Bitmap(lib,&face->glyph->outline,&bm);for(auto& v:mask) v=v>=128;
                    const auto check=cs::compare(sdf,mask,w,h,k);
                    auto t2=std::chrono::steady_clock::now();
                    if(check.failed()) {cs::fromMask(mask,w,h,k,spread);++failed;++fontFailed;
                        if(check.holes+check.spills>worstHoles+worstSpills){worstHoles=check.holes;worstSpills=check.spills;worstGlyph=gid;}}
                    auto t3=std::chrono::steady_clock::now();
                    sdfMs+=std::chrono::duration<double,std::milli>(t1-t0).count();checkMs+=std::chrono::duration<double,std::milli>(t2-t1).count();
                    rebuildMs+=std::chrono::duration<double,std::milli>(t3-t2).count();++total;
                }
                std::cout<<name.substr(0,24)<<": failed "<<fontFailed<<" worst gid "<<worstGlyph<<" holes="<<worstHoles<<" spills="<<worstSpills<<'\n';
                FT_Done_Face(face);
            }
            std::cout<<"ppem "<<ppem<<": "<<total<<" glyphs, "<<failed<<" failed; FreeType SDF "<<sdfMs/total<<" ms/glyph, check "<<checkMs/total
                <<" ms/glyph, rebuild "<<(failed?rebuildMs/failed:0)<<" ms/failed glyph\n";
            FT_Done_FreeType(lib);
        } else if(command=="sdfcheck" && argc>=4) {
            // TEXT FIX 9: compare the engine SDF's inside/outside sign with an independent FreeType coverage raster.
            const std::string fontArg(argv[3]);
            std::vector<FontInput> set=inputs;std::string id=fontArg;
            std::vector<std::pair<uint32_t,float>> variation;
            if(argc>=6 && std::string(argv[5]).find('=')!=std::string::npos) { const std::string v(argv[5]);
                variation.push_back({static_cast<uint32_t>(v[0])<<24|static_cast<uint32_t>(v[1])<<16|static_cast<uint32_t>(v[2])<<8|static_cast<uint32_t>(v[3]),std::stof(v.substr(5))}); }
            if(fontArg.rfind("fluxx.",0)!=0) {id="import.check";set.push_back({id,read(fontArg),0,-1,variation});}
            FontManager checked(std::move(set));
            const std::string chars=argc>=5?argv[4]:"gpqyHabo0&";
            const unsigned tierArg=argc>=6 && std::string(argv[5]).find('=')==std::string::npos?static_cast<unsigned>(std::stoul(argv[5])):0u;
            FT_Library ft=nullptr;require(FT_Init_FreeType(&ft)==0,"FT init");
            const auto bytes=fontArg.rfind("fluxx.",0)==0?std::vector<uint8_t>{}:read(fontArg);
            FT_Face face=nullptr;
            if(!bytes.empty()) require(FT_New_Memory_Face(ft,bytes.data(),static_cast<FT_Long>(bytes.size()),0,&face)==0,"FT face");
            else require(FT_New_Memory_Face(ft,inputs[fontArg=="fluxx.serif"?1:fontArg=="fluxx.mono"?2:0].bytes.data(),
                static_cast<FT_Long>(inputs[fontArg=="fluxx.serif"?1:fontArg=="fluxx.mono"?2:0].bytes.size()),0,&face)==0,"FT face");
            if(!variation.empty()) {
                FT_MM_Var* mm=nullptr;require(FT_Get_MM_Var(face,&mm)==0,"MM var");
                std::vector<FT_Fixed> coords(mm->num_axis);
                for(FT_UInt a=0;a<mm->num_axis;++a) { coords[a]=mm->axis[a].def;
                    for(const auto& v:variation) if(mm->axis[a].tag==v.first) coords[a]=static_cast<FT_Fixed>(v.second*65536.f); }
                require(FT_Set_Var_Design_Coordinates(face,mm->num_axis,coords.data())==0,"coords");FT_Done_MM_Var(ft,mm);
            }
            for(char c:chars) for(int overlaps=1;overlaps>=0;--overlaps) {
                const auto gid=checked.glyphForCodepoint(id,static_cast<uint32_t>(static_cast<unsigned char>(c)));
                const auto sdf=checked.sdf(id,gid,overlaps!=0,tierArg);
                require(FT_Set_Pixel_Sizes(face,0,sdf->ppem)==0,"size");
                require(FT_Load_Glyph(face,gid,FT_LOAD_NO_HINTING|FT_LOAD_NO_BITMAP|FT_LOAD_RENDER)==0,"render");
                const auto& cov=face->glyph->bitmap;
                int holes=0,spill=0,ink=0;
                std::string art;
                for(int y=0;y<sdf->height;++y) {
                    for(int x=0;x<sdf->width;++x) {
                        // SDF bearings include the spread; map each SDF pixel to the coverage raster.
                        const int cx=x+sdf->left-face->glyph->bitmap_left,cy=y-sdf->top+face->glyph->bitmap_top;
                        const int coverage=(cx>=0&&cy>=0&&cx<static_cast<int>(cov.width)&&cy<static_cast<int>(cov.rows))?cov.buffer[cy*cov.pitch+cx]:0;
                        const bool inside=sdf->pixels[y*sdf->width+x]>=128;
                        if(coverage>=224) {++ink;if(!inside) ++holes;}
                        if(coverage==0 && inside) ++spill;
                        if(argc>=7) art+=coverage>=224&&!inside?'X':inside?'#':coverage>0?'+':'.';
                    }
                    if(argc>=7) art+='\n';
                }
                std::cout<<"'"<<c<<"' gid="<<gid<<" overlaps="<<overlaps<<" ppem="<<sdf->ppem<<" spread="<<sdf->spread<<" "<<sdf->width<<"x"<<sdf->height
                    <<" ink="<<ink<<" holes="<<holes<<" spill="<<spill<<'\n';
                if(argc>=7 && overlaps==1) std::cout<<art;
            }
            FT_Done_Face(face);FT_Done_FreeType(ft);
        } else if(command=="inspect" && argc==4) {
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
            {
                const auto before=manager.stats().shapes;
                const auto nominal=manager.nominal("fluxx.serif","office");
                require(nominal->glyphs.size()==6,"Standalone cmap lookup formed a ligature");
                require(manager.stats().shapes==before,"Standalone catalogue invoked shaping");
                const auto packet=encodePreparedText(*nominal,manager);
                require(layoutMetrics(*decodePreparedText(packet.data(),packet.size()))==layoutMetrics(*nominal),"Standalone catalogue transport changed metrics");
            }
            {
                const std::vector<BreakCluster> letters{{10,false,false},{5,true,true},{10,false,false},{5,true,true},{10,false,false}};
                ParagraphFormat paragraph;paragraph.end=5;
                const auto wrapped=layoutParagraphs(letters,{paragraph},26,20);
                const WrapCluster raw[]={{10,0,0,0},{5,1,1,0},{10,0,0,0},{5,1,1,0},{10,0,0,0}};
                const WrapParagraph rawParagraph{0,5,0,0,0,0,0,0,0};
                double rawPositions[5]{};WrapLine rawLines[256]{};
                for(unsigned frame=0;frame<600;++frame) {
                    const auto count=wrapParagraphs(raw,5,&rawParagraph,1,26,20,rawPositions,rawLines,256);
                    require(count==wrapped.lines.size(),"Reusable wrapping changed line count");
                    for(unsigned line=0;line<count;++line) require(rawLines[line].begin==wrapped.lines[line].begin && rawLines[line].end==wrapped.lines[line].end && rawLines[line].advance==wrapped.lines[line].advance,"Reusable/native reference wrapping differs");
                    for(unsigned cluster=0;cluster<5;++cluster) require(rawPositions[cluster]==wrapped.positions[cluster],"Reusable wrapped positions differ");
                }
                const WrapCluster tracked[]={{10,0,0,10},{10,0,0,10},{10,0,0,0}};
                const WrapParagraph trackedParagraph{0,3,0,0,0,0,0,0,0};
                require(wrapParagraphs(tracked,3,&trackedParagraph,1,30,20,rawPositions,rawLines,256)==2 && rawLines[0].end==2 && rawLines[0].advance==30,"Wrapping counted tracking after the line's final cluster");
                bool lineCap=false;try { wrapParagraphs(tracked,3,&trackedParagraph,1,1,20,rawPositions,rawLines,2); } catch(const std::invalid_argument&) { lineCap=true; }
                require(lineCap,"Wrapped line overflow did not reject at the prepared cap");
                require(wrapped.lines.size()==2 && wrapped.lines[0].end==4 && wrapped.lines[0].advance==25,"Word wrapping/trailing-space hanging failed");
                paragraph.alignment=ParagraphAlign::JustifyLastRight;
                const auto justified=layoutParagraphs(letters,{paragraph},26,20);
                require(justified.lines[0].wordExpansion==1 && justified.lines[1].origin==16,"Justification/last-right alignment failed");
                paragraph.alignment=ParagraphAlign::Centre;
                const auto centred=layoutParagraphs(letters,{paragraph},60,20);
                require(centred.lines[0].origin==10 && centred.lines[0].advance==40,"Box centre alignment failed");
                paragraph.first=7;paragraph.left=3;paragraph.right=2;
                const auto indented=layoutParagraphs(letters,{paragraph},0,20);
                require(indented.lines.size()==1 && indented.lines[0].origin==10,"Point text unexpectedly wrapped or lost indent");
                ParagraphFormat empty;empty.before=99;empty.after=3;
                ParagraphFormat next;next.end=5;next.before=4;
                const auto paragraphs=layoutParagraphs(letters,{empty,next},0,20);
                require(paragraphs.lines.size()==2 && paragraphs.lines[0].top==0 && paragraphs.lines[1].top==27,"Empty paragraph/first space-before semantics failed");
                const auto overflow=layoutParagraphs({{40,false,false},{20,false,false}},{ParagraphFormat{0,2}},8,20);
                require(overflow.lines.size()==2 && overflow.lines[0].advance==40,"Unbreakable cluster overflow was clipped");
                bool rejected=false;try { layoutParagraphs(letters,{ParagraphFormat{0,4}},26,20); } catch(const std::invalid_argument&) { rejected=true; }
                require(rejected,"Incomplete paragraph coverage accepted");
            }
            for(const auto& input:inputs) {
                const auto metadata=inspectFont(input.bytes.data(),input.bytes.size());
                require(metadata.find("\"version\":1")!=std::string::npos && metadata.find("\"axes\":[]")!=std::string::npos,"Static face inspection metadata missing");
            }
            {
                uint32_t cp=0;
                for(uint32_t candidate=0x1f00;candidate<0x2000;++candidate)
                    if(!manager.glyphForCodepoint("fluxx.mono",candidate) && manager.glyphForCodepoint("fluxx.serif",candidate)) { cp=candidate;break; }
                require(cp!=0,"Pinned corpus has no fallback coverage fixture");
                std::string value;value.push_back(static_cast<char>(0xe0|(cp>>12)));
                value.push_back(static_cast<char>(0x80|((cp>>6)&63)));value.push_back(static_cast<char>(0x80|(cp&63)));
                StyledRun run;run.start=0;run.end=3;run.fontId="fluxx.mono";
                const auto fallback=manager.shapeStyled("fluxx.mono",value,{run},{cp});
                require(fallback->glyphs.front().id!=0 && fallback->glyphFont(0)!="fluxx.mono","Styled glyph coverage did not select a bundled fallback");
                require(fallback->characters.size()==1 && fallback->clusters.size()==1,"Font fallback changed logical membership");
            }
            bool invalidFont=false;try { const uint8_t bytes[]={0,1,2,3};inspectFont(bytes,sizeof(bytes)); } catch(const std::invalid_argument&) { invalidFont=true; }
            require(invalidFont,"Invalid font accepted by inspection");
            {
                StyledRun first;first.start=0;first.end=1;first.fontId="fluxx.sans.bold";first.size=2;first.horizontal=1.5;first.fauxBold=true;first.fauxItalic=true;
                const auto empty=manager.shapeStyled("fluxx.sans","",{},{});
                const auto emptyPacket=encodePreparedText(*empty,manager);
                require(layoutMetrics(*decodePreparedText(emptyPacket.data(),emptyPacket.size()))==layoutMetrics(*empty),"Prepared empty text changed zero-glyph metrics");
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
                {
                    const std::string sample="Hello World 123!?";
                    const std::vector<uint32_t> mapping={'H','E','L','L','O',' ','W','O','R','L','D',' ','1','2','3','!','?'};
                    for(const auto& family:{"fluxx.sans","fluxx.serif","fluxx.mono"}) {
                        StyledRun run;run.start=0;run.end=static_cast<uint32_t>(sample.size());run.caps=2;
                        const auto small=manager.shapeStyled(family,sample,{run},mapping,false);
                        for(size_t g=0;g<small->glyphs.size();++g) {
                            const auto cp=sample[small->glyphs[g].cluster];
                            if(!(cp>='a' && cp<='z')) {
                                require(small->geometry[g].fontSize==1,"Small caps scaled a capital/digit/punctuation");
                                require(small->glyphs[g].id==manager.glyphForCodepoint(family,cp),"Small caps substituted a capital/digit/punctuation");
                            }
                        }
                        run.caps=0;run.fauxBold=true;run.fauxItalic=true;
                        for(unsigned script:{1u,2u}) {
                            run.script=script;
                            const auto positioned=manager.shapeStyled(family,sample,{run},mapping,false);
                            for(const auto& geometry:positioned->geometry) {
                                require(geometry.fontSize==.583,"Script did not synthesize every glyph");
                                require(geometry.baseline==(script==1?-.333:.333)*1000,"Script parent-em offset changed");
                            }
                        }
                    }
                    StyledRun run;run.start=0;run.end=4;run.caps=3;
                    const auto lower=manager.shapeStyled("fluxx.sans","Text",{run},{'t','e','x','t'},false);
                    require(lower->glyphs[0].id==manager.glyphForCodepoint("fluxx.sans",'t'),"Simple lowercase did not reach shaping");
                    require(lower->characters.size()==4 && lower->geometry[0].fontSize==1,"Lowercase changed size/source membership");
                }
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
