#include "font_inspection.h"
#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_MULTIPLE_MASTERS_H
#include FT_TRUETYPE_TABLES_H
#include FT_SFNT_NAMES_H
#include FT_TRUETYPE_IDS_H
#include <memory>
#include <sstream>
#include <iomanip>
#include <stdexcept>
#include <algorithm>
#include <hb.h>

namespace fluxx::text {
namespace {
void check(FT_Error error,const char* operation) {
    if(error) throw std::invalid_argument(std::string(operation)+" failed; choose a valid outline TTF, OTF or TTC font");
}
std::string quote(const std::string& value) {
    std::ostringstream out;out<<'"';
    for(unsigned char c:value) {
        if(c=='"' || c=='\\') out<<'\\'<<c;
        else if(c<32) out<<"\\u"<<std::hex<<std::setw(4)<<std::setfill('0')<<unsigned(c)<<std::dec;
        else out<<c;
    }
    out<<'"';return out.str();
}
void utf8(std::string& text,uint32_t cp) {
    if(cp<128) text.push_back(static_cast<char>(cp));
    else if(cp<2048) { text.push_back(static_cast<char>(0xc0|(cp>>6)));text.push_back(static_cast<char>(0x80|(cp&63))); }
    else if(cp<65536) { text.push_back(static_cast<char>(0xe0|(cp>>12)));text.push_back(static_cast<char>(0x80|((cp>>6)&63)));text.push_back(static_cast<char>(0x80|(cp&63))); }
    else { text.push_back(static_cast<char>(0xf0|(cp>>18)));text.push_back(static_cast<char>(0x80|((cp>>12)&63)));text.push_back(static_cast<char>(0x80|((cp>>6)&63)));text.push_back(static_cast<char>(0x80|(cp&63))); }
}
std::string name(FT_Face face,FT_UInt id,const char* fallback) {
    const auto count=FT_Get_Sfnt_Name_Count(face);
    if(count>8192) throw std::invalid_argument("Font name table is too large; choose another font");
    std::string candidate;
    for(FT_UInt i=0;i<count;++i) {
        FT_SfntName record{};if(FT_Get_Sfnt_Name(face,i,&record) || record.name_id!=id) continue;
        if(record.platform_id!=TT_PLATFORM_APPLE_UNICODE && record.platform_id!=TT_PLATFORM_MICROSOFT) continue;
        std::string value;unsigned units=0;
        for(FT_UInt j=0;j+1<record.string_len && units<128;j+=2,++units) {
            uint32_t cp=(uint32_t(record.string[j])<<8)|record.string[j+1];
            if(cp>=0xd800 && cp<=0xdbff && j+3<record.string_len) {
                const auto next=(uint32_t(record.string[j+2])<<8)|record.string[j+3];
                if(next>=0xdc00 && next<=0xdfff && units+2<=128) { cp=0x10000+((cp-0xd800)<<10)+(next-0xdc00);j+=2;++units; }
                else cp=0xfffd;
            } else if(cp>=0xd800 && cp<=0xdfff) cp=0xfffd;
            utf8(value,cp<32?' ':cp);
        }
        if(value.empty()) continue;
        candidate=value;
        if(record.platform_id==TT_PLATFORM_MICROSOFT && record.language_id==0x409) break;
    }
    if(!candidate.empty()) return candidate;
    std::string value=fallback?fallback:"";
    if(value.size()>128) value.resize(128);
    for(auto& c:value) if(static_cast<unsigned char>(c)<32) c=' ';
    return value.empty()?"Unnamed":value;
}
bool table(FT_Face face,FT_ULong tag) { FT_ULong size=0;return FT_Load_Sfnt_Table(face,tag,0,nullptr,&size)==0 && size>0; }
struct Library {
    FT_Library value=nullptr;
    Library() { check(FT_Init_FreeType(&value),"Opening FreeType"); }
    ~Library() { FT_Done_FreeType(value); }
};
}

std::string inspectFont(const uint8_t* bytes,size_t length) {
    if(!bytes || length<4 || length>16*1024*1024) throw std::invalid_argument("Font files must be at most 16 MiB");
    const uint32_t magic=(uint32_t(bytes[0])<<24)|(uint32_t(bytes[1])<<16)|(uint32_t(bytes[2])<<8)|bytes[3];
    if(magic!=0x00010000 && magic!=FT_MAKE_TAG('O','T','T','O') && magic!=FT_MAKE_TAG('t','r','u','e') && magic!=FT_MAKE_TAG('t','t','c','f'))
        throw std::invalid_argument("Only TTF, OTF and TTC containers are supported");
    Library library;
    auto open=[&](FT_Long index) {
        FT_Face raw=nullptr;check(FT_New_Memory_Face(library.value,bytes,static_cast<FT_Long>(length),index,&raw),"Opening font face");
        return std::unique_ptr<FT_FaceRec,decltype(&FT_Done_Face)>(raw,FT_Done_Face);
    };
    const auto first=open(0);const auto faces=first->num_faces;
    if(faces<=0 || faces>256) throw std::invalid_argument("Font collections support at most 256 faces");
    std::ostringstream out;out<<std::setprecision(17)<<"{\"version\":1,\"faces\":[";
    for(FT_Long index=0;index<faces;++index) {
        const auto face=open(index);
        if(!FT_IS_SFNT(face.get()) || !FT_IS_SCALABLE(face.get()) || !table(face.get(),FT_MAKE_TAG('c','m','a','p')))
            throw std::invalid_argument("Only sfnt outline fonts with a Unicode cmap are supported; bitmap-only fonts are unsupported");
        if(table(face.get(),FT_MAKE_TAG('C','O','L','R')) || table(face.get(),FT_MAKE_TAG('C','B','D','T')) || table(face.get(),FT_MAKE_TAG('s','b','i','x')))
            throw std::invalid_argument("Colour font tables are unsupported; choose a monochrome outline font");
        check(FT_Select_Charmap(face.get(),FT_ENCODING_UNICODE),"Selecting Unicode cmap");
        if(face->num_glyphs<=0 || face->num_glyphs>65535) throw std::invalid_argument("Invalid outline glyph count");
        const auto* os2=static_cast<TT_OS2*>(FT_Get_Sfnt_Table(face.get(),ft_sfnt_os2));
        const auto* post=static_cast<TT_Postscript*>(FT_Get_Sfnt_Table(face.get(),ft_sfnt_post));
        const bool italic=(face->style_flags&FT_STYLE_FLAG_ITALIC)!=0;
        const auto family=name(face.get(),TT_NAME_ID_TYPOGRAPHIC_FAMILY,face->family_name);
        const auto style=name(face.get(),TT_NAME_ID_TYPOGRAPHIC_SUBFAMILY,face->style_name);
        const auto ps=name(face.get(),TT_NAME_ID_PS_NAME,FT_Get_Postscript_Name(face.get()));
        std::string preview="Aa AV office";
        if(std::any_of(preview.begin(),preview.end(),[&](unsigned char cp) { return FT_Get_Char_Index(face.get(),cp)==0; })) {
            preview.clear();FT_UInt glyph=0;auto cp=FT_Get_First_Char(face.get(),&glyph);unsigned accepted=0,scanned=0;
            while(glyph && accepted<12 && scanned++<65536) {
                const auto category=hb_unicode_general_category(hb_unicode_funcs_get_default(),static_cast<hb_codepoint_t>(cp));
                if(cp>=0x20 && cp<=0x10ffff && !(cp>=0x7f && cp<0xa0) && !(cp>=0xd800 && cp<=0xdfff) &&
                    category!=HB_UNICODE_GENERAL_CATEGORY_FORMAT && category!=HB_UNICODE_GENERAL_CATEGORY_NON_SPACING_MARK &&
                    category!=HB_UNICODE_GENERAL_CATEGORY_SPACING_MARK && category!=HB_UNICODE_GENERAL_CATEGORY_ENCLOSING_MARK) {
                    if(!preview.empty()) preview.push_back(' ');
                    utf8(preview,static_cast<uint32_t>(cp));++accepted;
                }
                cp=FT_Get_Next_Char(face.get(),cp,&glyph);
            }
            if(preview.empty()) throw std::invalid_argument("Font has no printable Unicode preview glyphs; choose another outline font");
        }
        if(index) out<<',';
        out<<"{\"index\":"<<index<<",\"family\":"<<quote(family)<<",\"style\":"<<quote(style)<<",\"postscript\":"<<quote(ps)
            <<",\"preview\":"<<quote(preview)<<",\"weight\":"<<(os2 && os2->usWeightClass?os2->usWeightClass:((face->style_flags&FT_STYLE_FLAG_BOLD)?700:400))
            <<",\"width\":"<<(os2 && os2->usWidthClass?os2->usWidthClass:5)<<",\"slant\":"<<(post?post->italicAngle/65536.0:(italic?-12.0:0.0))<<",\"axes\":[";
        FT_MM_Var* raw=nullptr;
        if(FT_HAS_MULTIPLE_MASTERS(face.get())) check(FT_Get_MM_Var(face.get(),&raw),"Reading font axes");
        const auto release=[&](FT_MM_Var* value) { if(value) FT_Done_MM_Var(library.value,value); };
        std::unique_ptr<FT_MM_Var,decltype(release)> axes(raw,release);
        if(axes && (axes->num_axis>16 || axes->num_namedstyles>256)) throw std::invalid_argument("Fonts support at most 16 axes and 256 named instances");
        if(axes) for(FT_UInt a=0;a<axes->num_axis;++a) {
            const auto& axis=axes->axis[a];std::string tag;
            for(int byte=3;byte>=0;--byte) { const auto c=static_cast<char>((axis.tag>>(byte*8))&255);if(c<32 || c>126) throw std::invalid_argument("Invalid font axis tag");tag.push_back(c); }
            if(axis.minimum>axis.def || axis.def>axis.maximum) throw std::invalid_argument("Invalid font axis bounds");
            if(a) out<<',';
            out<<"{\"tag\":"<<quote(tag)<<",\"name\":"<<quote(name(face.get(),axis.strid,axis.name))
                <<",\"min\":"<<axis.minimum/65536.0<<",\"default\":"<<axis.def/65536.0<<",\"max\":"<<axis.maximum/65536.0<<'}';
        }
        out<<"],\"instances\":[";
        if(axes) for(FT_UInt instance=0;instance<axes->num_namedstyles;++instance) {
            if(instance) out<<',';
            out<<"{\"index\":"<<instance<<",\"name\":"<<quote(name(face.get(),axes->namedstyle[instance].strid,"Instance"))<<",\"values\":[";
            for(FT_UInt a=0;a<axes->num_axis;++a) { if(a) out<<',';out<<axes->namedstyle[instance].coords[a]/65536.0; }
            out<<"]}";
        }
        out<<"]}";
        if(out.tellp()>4*1024*1024) throw std::invalid_argument("Font metadata exceeds 4 MiB; choose a smaller collection");
    }
    out<<"]}";return out.str();
}
}
