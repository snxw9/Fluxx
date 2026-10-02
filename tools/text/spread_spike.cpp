// Host-only spread/capacity comparison. Production defaults remain 48/8.
#include "font_manager.h"
#include "atlas_packer.h"
#include "text_diagnostics.h"
#include <filesystem>
#include <fstream>
#include <iostream>
#include <cmath>
#include <algorithm>
using namespace fluxx::text;
static std::vector<uint8_t> read(const std::filesystem::path& path) {
    std::ifstream f(path,std::ios::binary); if(!f) throw std::runtime_error("Missing font: "+path.string());
    return {std::istreambuf_iterator<char>(f),std::istreambuf_iterator<char>()};
}
static float smooth(float x,float lo,float hi) {
    float t=std::clamp((x-lo)/(hi-lo),0.0f,1.0f); return t*t*(3-2*t);
}
int main(int argc,char** argv) {
    try {
        if(argc!=3) throw std::runtime_error("Usage: spread-spike FONT_DIRECTORY OUTPUT_DIRECTORY");
        std::filesystem::path root(argv[1]),output(argv[2]); std::filesystem::create_directories(output);
        std::ofstream capacity(output/"capacity.csv");
        std::ofstream quality(output/"samples.csv");
        capacity << "ppem,spread,glyphs,total_bitmap_area,page1_fit,page2_fit,pages_for_corpus,rejected_two_pages\n";
        quality << "font,glyph,ppem,spread,size,half_em,requested_soft_em,effective_soft_em,coverage_area,edge_max\n";
        std::cout << "ppem,spread,glyphs,total_bitmap_area,page1_fit,page2_fit,pages_for_corpus,rejected_two_pages\n";
        for(auto ppem:{48u,64u}) {
            const unsigned spread=ppem==48?8:12;
            FontManager manager({{"fluxx.sans",read(root/"Inter-Regular.ttf")},{"fluxx.serif",read(root/"NotoSerif-Regular.ttf")},
                {"fluxx.mono",read(root/"JetBrainsMono-Regular.ttf")}},64*1024*1024,ppem,spread);
            std::vector<std::shared_ptr<const SdfGlyph>> corpus;
            uint64_t area=0;
            for(const char* font:{"fluxx.sans","fluxx.serif","fluxx.mono"}) for(auto id:manager.latinGlyphs(font)) {
                auto glyph=manager.sdf(font,id); area+=static_cast<uint64_t>(glyph->width)*glyph->height; corpus.push_back(glyph);
            }
            auto packed=packGlyphPages(corpus,4); auto two=packGlyphPages(corpus,2);
            std::cout << ppem << ',' << spread << ',' << corpus.size() << ',' << area << ','
                << (packed.pages.empty()?0:packed.pages[0].placements.size()) << ','
                << (packed.pages.size()<2?0:packed.pages[1].placements.size()) << ',' << packed.pages.size() << ',' << two.rejected << '\n';
            capacity << ppem << ',' << spread << ',' << corpus.size() << ',' << area << ','
                << (packed.pages.empty()?0:packed.pages[0].placements.size()) << ','
                << (packed.pages.size()<2?0:packed.pages[1].placements.size()) << ',' << packed.pages.size() << ',' << two.rejected << '\n';
            // CPU reference visualizations use sampled production FreeType SDFs, not device shader measurements.
            for(const char* font:{"fluxx.sans","fluxx.serif","fluxx.mono"}) for(char ch:{'A','V','g','y'}) {
                auto glyph=manager.sdf(font,manager.glyphForCodepoint(font,ch));
                for(unsigned size:{8u,72u,500u}) for(float halfEm:{0.0f,0.05f,0.10f}) for(float softEm:{0.0f,0.03f}) {
                    const float scale=static_cast<float>(size)/ppem;
                    // Identical em-aligned canvas across settings supports direct sample differences.
                    int w=static_cast<int>(std::ceil(size*1.5f)),h=w;
                    std::vector<uint8_t> pixels(static_cast<size_t>(w)*h);
                    const float half=halfEm*ppem/(2*spread),soft=std::min(softEm,0.10f-halfEm)*ppem/(2*spread);
                    const float aa=std::min(ppem/(2.0f*spread*size),.125f)*.5f;
                    double coverageArea=0; unsigned edgeMax=0;
                    for(int y=0;y<h;++y) for(int x=0;x<w;++x) {
                        const float gx=(x+.5f)/scale-ppem*.25f-glyph->left-.5f,gy=(y+.5f)/scale-ppem+glyph->top-.5f;
                        const int ix=static_cast<int>(std::floor(gx)),iy=static_cast<int>(std::floor(gy));
                        auto sample=[&](int sx,int sy){return sx<0 || sy<0 || sx>=glyph->width || sy>=glyph->height ? 0.0f : glyph->pixels[static_cast<size_t>(sy)*glyph->width+sx]/255.0f;};
                        float fx=gx-ix,fy=gy-iy;
                        float d=(sample(ix,iy)*(1-fx)+sample(ix+1,iy)*fx)*(1-fy)+(sample(ix,iy+1)*(1-fx)+sample(ix+1,iy+1)*fx)*fy;
                        const float outer=smooth(d,128.0f/255-half-aa-soft,128.0f/255-half+aa+soft);
                        pixels[static_cast<size_t>(y)*w+x]=static_cast<uint8_t>(std::round(255*outer));
                        coverageArea+=outer;
                        if(x==0 || y==0 || x==w-1 || y==h-1) edgeMax=std::max(edgeMax,static_cast<unsigned>(pixels[static_cast<size_t>(y)*w+x]));
                    }
                    auto name=std::string(font)+"-"+ch+"-"+std::to_string(ppem)+"-"+std::to_string(size)+"-half"+std::to_string(halfEm)+"-soft"+std::to_string(softEm)+".png";
                    writeGrayPng(output/name,w,h,pixels);
                    quality << font << ',' << ch << ',' << ppem << ',' << spread << ',' << size << ',' << halfEm << ',' << softEm << ','
                        << std::min(softEm,0.10f-halfEm) << ',' << coverageArea << ',' << edgeMax << '\n';
                }
            }
        }
    } catch(const std::exception& e) { std::cerr<<e.what()<<'\n';return 1; }
}
