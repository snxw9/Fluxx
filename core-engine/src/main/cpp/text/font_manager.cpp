#include "font_manager.h"
#include "sdf_tiers.h"
#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_MODULE_H
#include FT_MULTIPLE_MASTERS_H
#include FT_OUTLINE_H
#include <hb.h>
#include <hb-ot.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
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
    unsigned ppem = Ppem, spread = Spread;
    uint64_t shapes=0;
    uint64_t shapingNs=0,rasterNs=0;
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

FontManager::FontManager(std::vector<FontInput> fonts, size_t budget, unsigned ppem, unsigned spread) : impl_(std::make_unique<Impl>(budget)) {
    if (!ppem || spread < 2 || spread > 32) throw std::invalid_argument("Invalid SDF settings");
    impl_->ppem = ppem; impl_->spread = spread;
    ftCheck(FT_Init_FreeType(&impl_->library), "FT_Init_FreeType");
    FT_Int major = 0, minor = 0, patch = 0;
    FT_Library_Version(impl_->library, &major, &minor, &patch);
    if (major != 2 || minor != 14 || patch != 1 || std::string(hb_version_string()) != "14.3.1")
        throw std::runtime_error("Unexpected native font library version");
    // Spike-only settings are fixed for the manager lifetime; production keeps defaults.
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
        if(face->input.faceIndex>255 || face->input.namedInstance< -1 || face->input.namedInstance>255 || face->input.variations.size()>16)
            throw std::invalid_argument("Invalid font face or variation limits");
        const FT_Long instanceIndex=face->input.namedInstance<0?0:static_cast<FT_Long>(face->input.namedInstance+1)<<16;
        ftCheck(FT_New_Memory_Face(impl_->library, face->input.bytes.data(), static_cast<FT_Long>(face->input.bytes.size()),
            instanceIndex|face->input.faceIndex,&face->ft), "FT_New_Memory_Face");
        if (!FT_IS_SFNT(face->ft) || !FT_IS_SCALABLE(face->ft))
            throw std::invalid_argument("Expected scalable sfnt outline face");
        ftCheck(FT_Select_Charmap(face->ft, FT_ENCODING_UNICODE), "Unicode charmap");
        face->blob = hb_blob_create(reinterpret_cast<const char*>(face->input.bytes.data()), static_cast<unsigned>(face->input.bytes.size()), HB_MEMORY_MODE_READONLY, nullptr, nullptr);
        face->face = hb_face_create(face->blob, face->input.faceIndex);
        face->font = hb_font_create(face->face);
        hb_ot_font_set_funcs(face->font);
        const unsigned upem = hb_face_get_upem(face->face);
        if (!upem || upem != face->ft->units_per_EM) throw std::runtime_error("Font metric disagreement");
        hb_font_set_scale(face->font, static_cast<int>(upem), static_cast<int>(upem));
        if(FT_HAS_MULTIPLE_MASTERS(face->ft)) {
            FT_MM_Var* raw=nullptr;ftCheck(FT_Get_MM_Var(face->ft,&raw),"Read variation axes");
            const auto release=[&](FT_MM_Var* value) { if(value) FT_Done_MM_Var(impl_->library,value); };
            const std::unique_ptr<FT_MM_Var,decltype(release)> axes(raw,release);
            if(!axes || axes->num_axis>16) throw std::invalid_argument("Variable fonts support at most 16 axes");
            std::vector<FT_Fixed> coordinates(axes->num_axis);
            ftCheck(FT_Get_Var_Design_Coordinates(face->ft,axes->num_axis,coordinates.data()),"Read variation coordinates");
            std::set<uint32_t> seen;
            for(const auto& requested:face->input.variations) {
                if(!std::isfinite(requested.second) || !seen.insert(requested.first).second) throw std::invalid_argument("Invalid variation coordinate");
                FT_UInt a=0;while(a<axes->num_axis && axes->axis[a].tag!=requested.first) ++a;
                if(a==axes->num_axis) throw std::invalid_argument("Unknown variable-font axis");
                const auto& axis=axes->axis[a];
                const double fixed=std::clamp(double(requested.second)*65536.0,double(axis.minimum),double(axis.maximum));
                coordinates[a]=static_cast<FT_Fixed>(std::round(fixed));
            }
            ftCheck(FT_Set_Var_Design_Coordinates(face->ft,axes->num_axis,coordinates.data()),"Set variation coordinates");
            std::vector<hb_variation_t> variations(axes->num_axis);
            for(FT_UInt a=0;a<axes->num_axis;++a) variations[a]={static_cast<hb_tag_t>(axes->axis[a].tag),static_cast<float>(coordinates[a]/65536.0)};
            hb_font_set_variations(face->font,variations.data(),static_cast<unsigned>(variations.size()));
        } else if(!face->input.variations.empty() || face->input.namedInstance>=0) throw std::invalid_argument("Static fonts have no axes or named instances");
        hb_font_make_immutable(face->font);
        impl_->faces.push_back(std::move(face));
    }
    if (!ids.count("fluxx.sans")) throw std::invalid_argument("Default font required");
}
FontManager::~FontManager() = default;

std::shared_ptr<const Layout> FontManager::shape(const std::string& id, const std::string& text, bool ligatures,bool kerning,unsigned features) {
    const auto began=std::chrono::steady_clock::now();
    validateUtf8(text);
    auto guard = impl_->lock();
    auto& face = impl_->resolve(id);
    const std::string key = face.input.id + '\0' + (ligatures ? "1" : "0") + (kerning?"1":"0") + std::to_string(features)+ '\0'+text;
    for (auto entry = impl_->layouts.begin(); entry != impl_->layouts.end(); ++entry) {
        if (entry->key == key) {
            auto value = entry->value;
            impl_->layouts.splice(impl_->layouts.end(), impl_->layouts, entry);
            return value;
        }
    }
    ++impl_->shapes;
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
        hb_feature_t settings[4];unsigned settingsCount=0;
        if(!ligatures) {
            settings[settingsCount++]={HB_TAG('l','i','g','a'),0,0,UINT32_MAX};
            settings[settingsCount++]={HB_TAG('c','l','i','g'),0,0,UINT32_MAX};
        }
        if(!kerning) settings[settingsCount++]={HB_TAG('k','e','r','n'),0,0,UINT32_MAX};
        if(features>1) throw std::invalid_argument("Only lowercase smcp is supported; script positioning is synthetic");
        if(features&1u) settings[settingsCount++]={HB_TAG('s','m','c','p'),1,0,UINT32_MAX};
        hb_shape(face.font, buffer.get(), settings, settingsCount);
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
    // Source characters are Latin graphemes, distinct from ligature clusters. F2 can
    // project a selected character span onto an indivisible glyph cluster without reshaping.
    uint32_t sourceLine=0,word=0,nonSpace=0; bool inWord=false;
    for(size_t offset=0;offset<text.size();) {
        const auto begin=static_cast<uint32_t>(offset);
        uint32_t cp=static_cast<uint8_t>(text[offset++]);
        unsigned extra=0;
        if(cp>=0xf0) { cp&=7; extra=3; } else if(cp>=0xe0) { cp&=15; extra=2; } else if(cp>=0xc0) { cp&=31; extra=1; }
        while(extra--) cp=(cp<<6)|(static_cast<uint8_t>(text[offset++])&63);
        if(cp=='\n') { ++sourceLine; inWord=false; continue; }
        const auto category=hb_unicode_general_category(hb_unicode_funcs_get_default(),cp);
        const bool mark=category==HB_UNICODE_GENERAL_CATEGORY_NON_SPACING_MARK ||
            category==HB_UNICODE_GENERAL_CATEGORY_SPACING_MARK || category==HB_UNICODE_GENERAL_CATEGORY_ENCLOSING_MARK;
        if(mark && !result->characters.empty() && result->characters.back().line==sourceLine) {
            result->characters.back().byteEnd=static_cast<uint32_t>(offset); continue;
        }
        const bool space=cp==' ' || cp=='\t' || cp=='\v' || cp=='\f' || cp==0xa0 || cp==0x1680 ||
            (cp>=0x2000 && cp<=0x200a) || cp==0x202f || cp==0x205f || cp==0x3000;
        if(!space && !inWord) ++word;
        inWord=!space;
        result->characters.push_back({begin,static_cast<uint32_t>(offset),sourceLine,
            space?UINT32_MAX:word-1,space?UINT32_MAX:nonSpace++,space});
    }
    result->lineClusterCounts.resize(result->lineAdvances.size());
    size_t character=0;
    for(size_t i=0;i<result->glyphs.size();++i) {
        const auto& g=result->glyphs[i];
        if(i==0 || g.cluster!=result->glyphs[i-1].cluster || g.line!=result->glyphs[i-1].line) {
            size_t next=i+1;
            while(next<result->glyphs.size() && result->glyphs[next].cluster==g.cluster && result->glyphs[next].line==g.line) ++next;
            const auto newline=text.find('\n',g.cluster);
            const uint32_t end=next<result->glyphs.size() && result->glyphs[next].line==g.line ? result->glyphs[next].cluster :
                static_cast<uint32_t>(newline==std::string::npos ? text.size() : newline);
            while(character<result->characters.size() && result->characters[character].byteEnd<=g.cluster) ++character;
            if(character==result->characters.size()) throw std::logic_error("Missing source character membership");
            size_t characterEnd=character+1;
            while(characterEnd<result->characters.size() && result->characters[characterEnd].byteStart<end &&
                  result->characters[characterEnd].line==g.line) ++characterEnd;
            const auto& first=result->characters[character];
            const auto& last=result->characters[characterEnd-1];
            result->clusters.push_back({g.cluster,end,g.line,first.word,static_cast<uint32_t>(character),first.nonSpaceCharacter,first.space,
                static_cast<uint32_t>(characterEnd),last.space?UINT32_MAX:last.nonSpaceCharacter+1});
            ++result->lineClusterCounts[g.line];
        }
        result->glyphClusters.push_back(static_cast<uint32_t>(result->clusters.size()-1));
    }
    impl_->layouts.push_back({key, result});
    // 64 bounded layouts, each at most MaxTextBytes input. Leases survive eviction.
    if (impl_->layouts.size() > 64) impl_->layouts.pop_front();
    impl_->shapingNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-began).count();
    return result;
}

StyledInput decodeStyledInput(const uint8_t* bytes,size_t length) {
    if(length>1024*1024) throw std::invalid_argument("Rich text preparation exceeds 1 MiB");
    size_t at=0;
    auto integer=[&]() { if(length-at<4) throw std::invalid_argument("Truncated styled text input");
        const uint32_t value=(uint32_t(bytes[at])<<24)|(uint32_t(bytes[at+1])<<16)|(uint32_t(bytes[at+2])<<8)|bytes[at+3];at+=4;return value; };
    auto number=[&]() { const auto bits=integer();float value;std::memcpy(&value,&bits,4);
        if(!std::isfinite(value)) throw std::invalid_argument("Nonfinite styled text input");return double(value); };
    auto string=[&](size_t max) { const auto count=integer();if(count>max || count>length-at) throw std::invalid_argument("Invalid styled string length");
        std::string value(reinterpret_cast<const char*>(bytes+at),count);at+=count;return value; };
    if(integer()!=1) throw std::invalid_argument("Unsupported styled input version");
    StyledInput input;input.text=string(FontManager::MaxTextBytes);
    const auto count=integer();if(count>4096) throw std::invalid_argument("Too many uppercase codepoints");
    for(uint32_t i=0;i<count;++i) input.uppercase.push_back(integer());
    const auto runs=integer();if(runs>256) throw std::invalid_argument("Too many styled runs");
    for(uint32_t i=0;i<runs;++i) {
        StyledRun run;run.start=integer();run.end=integer();run.fontId=string(1024);
        run.size=number();run.horizontal=number();run.vertical=number();run.caps=integer();run.script=integer();
        const auto flags=integer();if(flags>7) throw std::invalid_argument("Unsupported styled run flags");
        run.kerning=flags&1;run.fauxBold=flags&2;run.fauxItalic=flags&4;input.runs.push_back(std::move(run));
    }
    if(at!=length) throw std::invalid_argument("Trailing styled text data");return input;
}

bool FontManager::hasFeature(const std::string& id,uint32_t tag) {
    auto guard=impl_->lock();auto& face=impl_->resolve(id);
    unsigned count=0;const auto total=hb_ot_layout_table_get_feature_tags(face.face,HB_OT_TAG_GSUB,0,&count,nullptr);
    std::vector<hb_tag_t> tags(total);count=total;
    hb_ot_layout_table_get_feature_tags(face.face,HB_OT_TAG_GSUB,0,&count,tags.data());
    return std::find(tags.begin(),tags.end(),tag)!=tags.end();
}

std::shared_ptr<const Layout> FontManager::shapeStyled(const std::string& id,const std::string& text,
    const std::vector<StyledRun>& runs,const std::vector<uint32_t>& uppercase,bool ligatures) {
    validateUtf8(text);
    if(runs.size()>256) throw std::invalid_argument("Rich text supports at most 256 runs");
    struct Point { uint32_t cp,start,end,line,upper; };
    std::vector<Point> points;uint32_t line=0;
    for(size_t at=0;at<text.size();) {
        const auto start=static_cast<uint32_t>(at);uint32_t cp=static_cast<uint8_t>(text[at++]);unsigned extra=0;
        if(cp>=0xf0) { cp&=7;extra=3; } else if(cp>=0xe0) { cp&=15;extra=2; } else if(cp>=0xc0) { cp&=31;extra=1; }
        while(extra--) cp=(cp<<6)|(static_cast<uint8_t>(text[at++])&63);
        if(points.size()>=uppercase.size()) throw std::invalid_argument("Uppercase map does not match source text");
        const auto upper=uppercase[points.size()];
        if(upper>0x10ffff || (upper>=0xd800 && upper<=0xdfff)) throw std::invalid_argument("Invalid uppercase scalar");
        points.push_back({cp,start,static_cast<uint32_t>(at),line,upper});if(cp=='\n') ++line;
    }
    if(points.size()!=uppercase.size()) throw std::invalid_argument("Uppercase map does not match source text");
    const auto source=shape(id,text,false);auto out=std::make_shared<Layout>();
    out->fontId=source->fontId;out->upem=1000;out->characters=source->characters;out->styled=true;
    out->ascent=source->ascent;out->descent=source->descent;out->lineGap=source->lineGap;
    const double base=1000.0/source->upem;
    out->styledAscent=0;out->styledDescent=0;out->styledLineGap=0;
    out->lineAdvances.resize(source->lineAdvances.size());out->styledLineAdvances.resize(source->lineAdvances.size());
    uint32_t end=0;size_t point=0;
    for(const auto& run:runs) {
        if(run.start!=end || run.end<=run.start || run.end>text.size() || !std::isfinite(run.size) || run.size<=0 ||
           !std::isfinite(run.horizontal) || run.horizontal<0 || !std::isfinite(run.vertical) || run.vertical<0 || run.caps>3 || run.script>2)
            throw std::invalid_argument("Invalid styled run");
        end=run.end;
        const auto requestedFont=run.fontId.empty()?id:run.fontId;
        auto font=requestedFont;bool small=false;unsigned features=0;double scriptScale=1,scriptOffset=0;
        auto configure=[&]() {
            small=run.caps==2 && hasFeature(font,HB_TAG('s','m','c','p'));features=small?1u:0u;
            // Synthetic script positioning applies uniformly, including capitals and imported faces.
            scriptScale=run.script?.583:1;
            scriptOffset=run.script?(run.script==1?-.333:.333)*run.size*1000:0;
        };
        configure();
        std::string display;std::vector<uint32_t> mapping;double synthetic=1;uint32_t chunkLine=0;
        auto append=[](std::string& s,uint32_t cp) {
            if(cp<0x80) s.push_back(static_cast<char>(cp));
            else if(cp<0x800) { s.push_back(static_cast<char>(0xc0|(cp>>6)));s.push_back(static_cast<char>(0x80|(cp&63))); }
            else if(cp<0x10000) { s.push_back(static_cast<char>(0xe0|(cp>>12)));s.push_back(static_cast<char>(0x80|((cp>>6)&63)));s.push_back(static_cast<char>(0x80|(cp&63))); }
            else { s.push_back(static_cast<char>(0xf0|(cp>>18)));s.push_back(static_cast<char>(0x80|((cp>>12)&63)));s.push_back(static_cast<char>(0x80|((cp>>6)&63)));s.push_back(static_cast<char>(0x80|(cp&63))); }
        };
        auto flush=[&]() {
            if(display.empty()) return;
            const auto part=shape(font,display,ligatures,run.kerning,features);
            auto found=std::find(out->fontIds.begin(),out->fontIds.end(),part->fontId);
            if(found==out->fontIds.end()) { out->fontIds.push_back(part->fontId);found=out->fontIds.end()-1; }
            const auto fontIndex=static_cast<uint32_t>(found-out->fontIds.begin());
            const double size=run.size*synthetic*scriptScale;
            const double fx=1000.0/part->upem*size*run.horizontal,fy=1000.0/part->upem*size*run.vertical;
            const double shear=run.fauxItalic?std::tan(12.0*3.14159265358979323846/180.0):0;
            const double boldX=run.fauxBold?.025*1000*size*run.horizontal:0,boldY=run.fauxBold?.025*1000*size*run.vertical:0;
            out->styledAscent=std::max(out->styledAscent,part->ascent*1000.0/part->upem*run.size);
            out->styledDescent=std::min(out->styledDescent,part->descent*1000.0/part->upem*run.size);
            out->styledLineGap=std::max(out->styledLineGap,part->lineGap*1000.0/part->upem*run.size);
            for(const auto& g:part->glyphs) {
                if(g.cluster>=mapping.size()) throw std::logic_error("Styled display cluster is outside its source map");
                auto glyph=g;glyph.cluster=mapping[g.cluster];glyph.line=chunkLine;out->glyphs.push_back(glyph);
                Layout::Geometry geometry;
                geometry.fontIndex=fontIndex;geometry.advance=g.xAdvance*fx;geometry.xOffset=g.xOffset*fx;
                geometry.yOffset=g.yOffset*fy-scriptOffset;
                const double bearing=g.yBearing*fy,height=g.inkHeight*fy;
                geometry.xBearing=g.xBearing*fx+std::min(shear*bearing,shear*(bearing+height))-boldX;
                geometry.yBearing=bearing+boldY;geometry.inkWidth=g.inkWidth*fx+std::abs(shear*height)+2*boldX;
                geometry.inkHeight=height-2*boldY;geometry.scaleX=size*run.horizontal;geometry.scaleY=size*run.vertical;
                if(g.inkWidth==0 || g.inkHeight==0) { geometry.inkWidth=0;geometry.inkHeight=0; }
                geometry.fontSize=size;geometry.baseline=scriptOffset;geometry.shear=shear;geometry.bold=run.fauxBold?.075:0;
                out->geometry.push_back(geometry);out->styledLineAdvances[chunkLine]+=geometry.advance;
            }
            display.clear();mapping.clear();
        };
        while(point<points.size() && points[point].start<run.end) {
            const auto current=point;const auto& p=points[point++];
            if(p.start<run.start || p.end>run.end) throw std::invalid_argument("Styled run splits a codepoint");
            if(p.cp=='\n') { flush();continue; }
            const auto category=hb_unicode_general_category(hb_unicode_funcs_get_default(),p.cp);
            const bool mark=category==HB_UNICODE_GENERAL_CATEGORY_NON_SPACING_MARK || category==HB_UNICODE_GENERAL_CATEGORY_SPACING_MARK || category==HB_UNICODE_GENERAL_CATEGORY_ENCLOSING_MARK;
            if(!mark) {
                // Keep a base and its combining marks in one face where coverage permits.
                size_t stop=point;
                while(stop<points.size() && points[stop].start<run.end) {
                    const auto kind=hb_unicode_general_category(hb_unicode_funcs_get_default(),points[stop].cp);
                    if(kind!=HB_UNICODE_GENERAL_CATEGORY_NON_SPACING_MARK && kind!=HB_UNICODE_GENERAL_CATEGORY_SPACING_MARK && kind!=HB_UNICODE_GENERAL_CATEGORY_ENCLOSING_MARK) break;
                    ++stop;
                }
                auto covers=[&](const std::string& candidate) {
                    const bool nativeSmall=run.caps==2 && hasFeature(candidate,HB_TAG('s','m','c','p'));
                    for(size_t q=current;q<stop;++q) {
                        auto cp=points[q].cp;
                        const bool lower=hb_unicode_general_category(hb_unicode_funcs_get_default(),cp)==HB_UNICODE_GENERAL_CATEGORY_LOWERCASE_LETTER;
                        if(run.caps==1 || run.caps==3 || (run.caps==2 && !nativeSmall && lower)) if(glyphForCodepoint(candidate,points[q].upper)) cp=points[q].upper;
                        if(!glyphForCodepoint(candidate,cp)) return false;
                    }
                    return true;
                };
                auto selected=requestedFont;
                if(!covers(selected)) for(const char* fallback:{"fluxx.sans","fluxx.serif","fluxx.mono"})
                    if(covers(fallback)) { selected=fallback;break; }
                if(selected!=font) { flush();font=selected;configure(); }
            }
            const bool lowercase=category==HB_UNICODE_GENERAL_CATEGORY_LOWERCASE_LETTER;
            // A feature's presence does not establish coverage of this glyph. Probe smcp
            // in preparation, then choose the native or synthetic path for this base.
            bool nativeSmall=small && lowercase;
            if(nativeSmall) {
                std::string probe;append(probe,p.cp);
                const auto mapped=shape(font,probe,false,run.kerning,1);
                nativeSmall=!mapped->glyphs.empty() && mapped->glyphs[0].id!=0 && mapped->glyphs[0].id!=glyphForCodepoint(font,p.cp);
            }
            const unsigned nextFeatures=mark && !display.empty()?features:(nativeSmall?1u:0u);
            const double scale=mark && !display.empty()?synthetic:(run.caps==2 && !nativeSmall && lowercase?.7:1);
            if(!display.empty() && (chunkLine!=p.line || synthetic!=scale || features!=nextFeatures)) flush();
            features=nextFeatures;
            synthetic=scale;chunkLine=p.line;
            uint32_t cp=p.cp;
            if(run.caps==1 || run.caps==3 || (run.caps==2 && !nativeSmall && lowercase)) if(glyphForCodepoint(font,p.upper)!=0) cp=p.upper;
            const auto before=display.size();append(display,cp);mapping.resize(display.size(),p.start);
            if(display.size()<=before) throw std::logic_error("Empty display mapping");
        }
        flush();
    }
    if(end!=text.size()) throw std::invalid_argument("Styled runs do not cover Source Text");
    if(out->glyphs.empty()) { out->styledAscent=source->ascent*base;out->styledDescent=source->descent*base;out->styledLineGap=source->lineGap*base; }
    out->lineClusterCounts.resize(out->lineAdvances.size());size_t character=0;
    for(size_t i=0;i<out->glyphs.size();++i) {
        const auto& g=out->glyphs[i];
        if(i==0 || g.cluster!=out->glyphs[i-1].cluster || g.line!=out->glyphs[i-1].line) {
            size_t next=i+1;while(next<out->glyphs.size() && out->glyphs[next].cluster==g.cluster && out->glyphs[next].line==g.line) ++next;
            const auto newline=text.find('\n',g.cluster);
            const uint32_t stop=next<out->glyphs.size() && out->glyphs[next].line==g.line?out->glyphs[next].cluster:
                static_cast<uint32_t>(newline==std::string::npos?text.size():newline);
            while(character<out->characters.size() && out->characters[character].byteEnd<=g.cluster) ++character;
            if(character==out->characters.size()) throw std::logic_error("Styled glyph has no source character");
            size_t last=character+1;while(last<out->characters.size() && out->characters[last].byteStart<stop && out->characters[last].line==g.line) ++last;
            const auto& first=out->characters[character];const auto& final=out->characters[last-1];
            out->clusters.push_back({g.cluster,stop,g.line,first.word,static_cast<uint32_t>(character),first.nonSpaceCharacter,first.space,
                static_cast<uint32_t>(last),final.space?UINT32_MAX:final.nonSpaceCharacter+1});++out->lineClusterCounts[g.line];
        }
        out->glyphClusters.push_back(static_cast<uint32_t>(out->clusters.size()-1));
    }
    return out;
}

std::shared_ptr<const SdfGlyph> FontManager::sdf(const std::string& id, uint32_t glyphId, bool overlaps, unsigned tierPpem) {
    auto guard = impl_->lock();
    auto& face = impl_->resolve(id);
    if (glyphId >= static_cast<uint32_t>(face.ft->num_glyphs)) throw std::out_of_range("Invalid glyph ID");
    if (tierPpem && !tiers::valid(tierPpem)) throw std::invalid_argument("Invalid SDF atlas tier");
    const unsigned ppem = tierPpem ? tierPpem : impl_->ppem;
    const unsigned spread = tierPpem ? tiers::spread(tierPpem) : impl_->spread;
    // Font catalog is immutable for this manager; resolved ID identifies the exact owned bytes. Tiers cache apart.
    const std::string key = face.input.id + ':' + std::to_string(glyphId) + (overlaps ? ":1" : ":0") +
        (ppem == impl_->ppem && spread == impl_->spread ? "" : "@" + std::to_string(ppem));
    impl_->evict();
    auto found = impl_->index.find(key);
    if (found != impl_->index.end()) {
        ++impl_->hits;
        impl_->rasters.splice(impl_->rasters.end(), impl_->rasters, found->second);
        return found->second->value;
    }
    const auto rasterStart=std::chrono::steady_clock::now();
    FT_Bool enabled = overlaps ? 1 : 0;
    ftCheck(FT_Property_Set(impl_->library, "sdf", "overlaps", &enabled), "Set SDF overlaps");
    // The spread is a library property: set it for every raster so tiers sharing a manager never mix spreads.
    FT_Int spreadValue = static_cast<FT_Int>(spread);
    ftCheck(FT_Property_Set(impl_->library, "sdf", "spread", &spreadValue), "Set SDF spread");
    ftCheck(FT_Set_Pixel_Sizes(face.ft, 0, ppem), "FT_Set_Pixel_Sizes");
    ftCheck(FT_Load_Glyph(face.ft, glyphId, FT_LOAD_NO_HINTING | FT_LOAD_NO_BITMAP), "FT_Load_Glyph");
    auto result = std::make_shared<SdfGlyph>();
    result->fontId = face.input.id; result->glyphId = glyphId; result->overlaps = overlaps;
    result->ppem = ppem; result->spread = spread;
    if (face.ft->glyph->outline.n_contours != 0) {
        FT_BBox bounds{};FT_Outline_Get_CBox(&face.ft->glyph->outline,&bounds);
        const double pixelWidth=std::ceil((double(bounds.xMax)-double(bounds.xMin))/64)+2*spread;
        const double pixelHeight=std::ceil((double(bounds.yMax)-double(bounds.yMin))/64)+2*spread;
        if(pixelWidth>2048 || pixelHeight>2048 || pixelWidth<0 || pixelHeight<0)
            throw std::invalid_argument("Font glyph exceeds the 2048-pixel SDF dimension limit; choose another font");
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
    impl_->rasterNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-rasterStart).count();
    return result;
}

uint32_t FontManager::glyphForCodepoint(const std::string& id, uint32_t codepoint) {
    auto guard = impl_->lock();
    return FT_Get_Char_Index(impl_->resolve(id).ft, codepoint);
}
void FontManager::seedSdfs(const std::vector<std::shared_ptr<const SdfGlyph>>& images) {
    auto guard=impl_->lock();
    for(const auto& image:images) {
        const auto found=std::find_if(impl_->faces.begin(),impl_->faces.end(),[&](const auto& face){return face->input.id==image->fontId;});
        if(found==impl_->faces.end() || image->glyphId>=static_cast<uint32_t>((*found)->ft->num_glyphs)) continue;
        const auto key=image->fontId+':'+std::to_string(image->glyphId)+(image->overlaps?":1":":0");
        if(impl_->index.count(key)) continue;
        impl_->rasters.push_back({key,image});impl_->index[key]=std::prev(impl_->rasters.end());
    }
    impl_->evict();
}
std::shared_ptr<const Layout> FontManager::nominal(const std::string& id,const std::string& text) {
    validateUtf8(text);
    auto guard=impl_->lock();auto& base=impl_->resolve(id);
    auto out=std::make_shared<Layout>();out->fontId=base.input.id;out->upem=1000;out->styled=true;
    out->styledAscent=base.ft->ascender*1000.0/base.ft->units_per_EM;
    out->styledDescent=base.ft->descender*1000.0/base.ft->units_per_EM;
    out->styledLineGap=(base.ft->height-base.ft->ascender+base.ft->descender)*1000.0/base.ft->units_per_EM;
    out->ascent=static_cast<int32_t>(out->styledAscent);out->descent=static_cast<int32_t>(out->styledDescent);
    out->lineGap=static_cast<int32_t>(out->styledLineGap);out->lineAdvances.resize(1);out->styledLineAdvances.resize(1);out->lineClusterCounts.resize(1);
    uint32_t nonSpace=0;
    for(size_t at=0;at<text.size();) {
        if(out->glyphs.size()>=4096) throw std::invalid_argument("Character catalogue exceeds 4096 codepoints; narrow the animated character range");
        const auto start=static_cast<uint32_t>(at);uint32_t cp=static_cast<uint8_t>(text[at++]);unsigned extra=0;
        if(cp>=0xf0) { cp&=7;extra=3; } else if(cp>=0xe0) { cp&=15;extra=2; } else if(cp>=0xc0) { cp&=31;extra=1; }
        while(extra--) cp=(cp<<6)|(static_cast<uint8_t>(text[at++])&63);
        auto* face=&base;auto gid=FT_Get_Char_Index(face->ft,cp);
        if(!gid) for(const char* fallback:{"fluxx.sans","fluxx.serif","fluxx.mono"}) {
            auto& candidate=impl_->resolve(fallback);const auto found=FT_Get_Char_Index(candidate.ft,cp);
            if(found) { face=&candidate;gid=found;break; }
        }
        hb_glyph_extents_t ink{};hb_font_get_glyph_extents(face->font,gid,&ink);
        const auto advance=hb_font_get_glyph_h_advance(face->font,gid);const double factor=1000.0/face->ft->units_per_EM;
        auto found=std::find(out->fontIds.begin(),out->fontIds.end(),face->input.id);
        if(found==out->fontIds.end()) { out->fontIds.push_back(face->input.id);found=out->fontIds.end()-1; }
        const auto index=static_cast<uint32_t>(out->glyphs.size());const bool space=cp==' ' || cp=='\t' || cp==0xa0;
        const auto member=space?UINT32_MAX:nonSpace++;
        out->characters.push_back({start,static_cast<uint32_t>(at),0,space?UINT32_MAX:0,member,space});
        out->clusters.push_back({start,static_cast<uint32_t>(at),0,space?UINT32_MAX:0,index,member,space,index+1,space?UINT32_MAX:member+1});
        out->glyphClusters.push_back(index);out->lineClusterCounts[0]++;
        out->glyphs.push_back({gid,start,0,advance,0,0,0,ink.x_bearing,ink.y_bearing,ink.width,ink.height});
        Layout::Geometry g;g.fontIndex=static_cast<uint32_t>(found-out->fontIds.begin());g.advance=advance*factor;
        g.xBearing=ink.x_bearing*factor;g.yBearing=ink.y_bearing*factor;g.inkWidth=ink.width*factor;g.inkHeight=ink.height*factor;
        out->geometry.push_back(g);out->styledLineAdvances[0]+=g.advance;
    }
    out->lineAdvances[0]=static_cast<int64_t>(out->styledLineAdvances[0]);return out;
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
    FontStats result{impl_->rasterizations, impl_->hits, impl_->waitNs, 0, 0, impl_->layouts.size(), impl_->shapes,impl_->shapingNs,impl_->rasterNs};
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
    const bool styled=layout.styled;
    if(styled) { result[1]=layout.styledAscent;result[2]=layout.styledDescent;result[3]=layout.styledLineGap; }
    for(size_t i=0;i<layout.lineAdvances.size();++i) result.push_back(layout.lineAdvance(i));
    double pen=0; uint32_t line=0;
    for(size_t index=0;index<layout.glyphs.size();++index) {
        const auto& glyph=layout.glyphs[index];
        if(glyph.line!=line) { line=glyph.line; pen=0; }
        if(styled) {
            const auto& g=layout.geometry[index];
            for(double value:{static_cast<double>(line),pen+g.xOffset,-g.yOffset,g.xBearing,g.yBearing,g.inkWidth,g.inkHeight}) result.push_back(value);
            pen+=g.advance;continue;
        }
        for(double value:{static_cast<double>(line),pen+glyph.xOffset,
            static_cast<double>(-glyph.yOffset),static_cast<double>(glyph.xBearing),
            static_cast<double>(glyph.yBearing),static_cast<double>(glyph.inkWidth),static_cast<double>(glyph.inkHeight)}) result.push_back(value);
        pen+=glyph.xAdvance;
    }
    for (auto value : layout.lineClusterCounts) result.push_back(value);
    uint32_t membershipLine = UINT32_MAX, ordinal = 0;
    uint32_t previous = UINT32_MAX;
    for (size_t i=0; i<layout.glyphs.size(); ++i) {
        if (layout.glyphs[i].line != membershipLine) { membershipLine=layout.glyphs[i].line; ordinal=0; previous=UINT32_MAX; }
        if (previous != UINT32_MAX && previous != layout.glyphClusters[i]) ++ordinal;
        result.push_back(ordinal); previous=layout.glyphClusters[i];
    }
    // F2 metadata follows the legacy metrics prefix; all preparation-only.
    uint32_t words=0;
    for(const auto& c:layout.characters) if(c.word!=UINT32_MAX) words=std::max(words,c.word+1);
    result.push_back(271828); result.push_back(static_cast<double>(layout.clusters.size()));
    result.push_back(static_cast<double>(layout.characters.size())); result.push_back(words);
    std::vector<double> starts(layout.clusters.size()), advances(layout.clusters.size());
    double clusterPen=0; uint32_t clusterLine=UINT32_MAX,previousCluster=UINT32_MAX;
    for(size_t i=0;i<layout.glyphs.size();++i) {
        const auto& g=layout.glyphs[i];const auto c=layout.glyphClusters[i];
        if(g.line!=clusterLine) { clusterPen=0;clusterLine=g.line;previousCluster=UINT32_MAX; }
        if(c!=previousCluster) starts[c]=static_cast<double>(clusterPen);
        const auto advance=styled?layout.geometry[i].advance:g.xAdvance;
        advances[c]+=advance;clusterPen+=advance;previousCluster=c;
    }
    for(size_t i=0;i<layout.clusters.size();++i) {
        const auto& c=layout.clusters[i];
        for(double v:{double(c.character),double(c.characterEnd),double(c.line),c.word==UINT32_MAX?-1.0:double(c.word),starts[i],advances[i]}) result.push_back(v);
    }
    for(const auto& c:layout.characters) for(double v:{double(c.line),c.word==UINT32_MAX?-1.0:double(c.word),
        c.nonSpaceCharacter==UINT32_MAX?-1.0:double(c.nonSpaceCharacter),c.space?1.0:0.0}) result.push_back(v);
    for(auto c:layout.glyphClusters) result.push_back(c);
    if(styled) {
        std::vector<double> sizes(layout.clusters.size(),1);
        for(size_t i=0;i<layout.glyphs.size();++i) sizes[layout.glyphClusters[i]]=layout.geometry[i].fontSize;
        result.push_back(314159);result.push_back(static_cast<double>(sizes.size()));
        result.insert(result.end(),sizes.begin(),sizes.end());
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
