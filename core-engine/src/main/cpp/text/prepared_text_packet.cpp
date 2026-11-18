#include "prepared_text_packet.h"
#include "sdf_tiers.h"
#include <chrono>
#include <cmath>
#include <cstring>
#include <map>
#include <stdexcept>
#include <algorithm>

namespace fluxx::text {
namespace {
struct Writer {
    std::vector<uint8_t> bytes;
    void integer(uint64_t value,unsigned count=4) {
        for(unsigned i=count;i>0;--i) bytes.push_back(static_cast<uint8_t>(value>>((i-1)*8)));
    }
    void real(double value) { uint64_t bits;std::memcpy(&bits,&value,8);integer(bits,8); }
    void string(const std::string& value) { integer(value.size());bytes.insert(bytes.end(),value.begin(),value.end()); }
};
struct Reader {
    const uint8_t* bytes;size_t length,at=0;
    uint64_t integer(unsigned count=4) {
        if(count>length-at) throw std::invalid_argument("Truncated prepared text packet");
        uint64_t value=0;for(unsigned i=0;i<count;++i) value=(value<<8)|bytes[at++];return value;
    }
    uint32_t count(uint32_t limit) { const auto value=integer();if(value>limit) throw std::invalid_argument("Prepared text exceeds safety limits");return static_cast<uint32_t>(value); }
    int32_t signedInteger() { const uint32_t value=static_cast<uint32_t>(integer());int32_t result;std::memcpy(&result,&value,4);return result; }
    double real() { const auto bits=integer(8);double value;std::memcpy(&value,&bits,8);
        if(!std::isfinite(value) || std::abs(value)>1e12) throw std::invalid_argument("Invalid prepared text geometry");return value; }
    std::string string() { const auto size=count(1024);if(size>length-at) throw std::invalid_argument("Truncated prepared text identity");
        std::string value(reinterpret_cast<const char*>(bytes+at),size);at+=size;
        if(value.empty() || std::any_of(value.begin(),value.end(),[](char c) { return static_cast<unsigned char>(c)<32; })) throw std::invalid_argument("Invalid prepared font identity");return value; }
};
using Key=std::pair<std::string,uint32_t>;
}

std::vector<uint8_t> encodePreparedText(const Layout& layout,FontManager& fonts) {
    if(!layout.styled) throw std::invalid_argument("Only styled layouts have a prepared transport");
    if(layout.glyphs.size()>MaxPreparedGlyphs) throw std::invalid_argument("Font substitutions exceed 16384 prepared glyphs; shorten text or choose another font");
    Writer out;out.integer(0x46545031);out.integer(1);out.string(layout.fontId);
    out.integer(layout.upem);out.integer(static_cast<uint32_t>(layout.ascent));out.integer(static_cast<uint32_t>(layout.descent));out.integer(static_cast<uint32_t>(layout.lineGap));
    out.real(layout.styledAscent);out.real(layout.styledDescent);out.real(layout.styledLineGap);
    out.integer(layout.fontIds.size());for(const auto& id:layout.fontIds) out.string(id);
    out.integer(layout.lineAdvances.size());
    for(size_t i=0;i<layout.lineAdvances.size();++i) { out.real(layout.lineAdvance(i));out.integer(layout.lineClusterCounts.at(i)); }
    std::map<Key,uint32_t> indices;std::vector<std::shared_ptr<const SdfGlyph>> images;
    out.integer(layout.glyphs.size());
    for(size_t i=0;i<layout.glyphs.size();++i) {
        const auto& g=layout.glyphs[i];const auto& v=layout.geometry.at(i);
        for(uint32_t field:{g.id,g.cluster,g.line,static_cast<uint32_t>(g.xAdvance),static_cast<uint32_t>(g.yAdvance),static_cast<uint32_t>(g.xOffset),static_cast<uint32_t>(g.yOffset),
            static_cast<uint32_t>(g.xBearing),static_cast<uint32_t>(g.yBearing),static_cast<uint32_t>(g.inkWidth),static_cast<uint32_t>(g.inkHeight)}) out.integer(field);
        out.integer(v.fontIndex);
        for(double field:{v.advance,v.xOffset,v.yOffset,v.xBearing,v.yBearing,v.inkWidth,v.inkHeight,v.scaleX,v.scaleY,v.fontSize,v.baseline,v.shear,v.bold}) out.real(field);
        out.integer(layout.glyphClusters.at(i));
        const Key key{layout.glyphFont(i),g.id};auto found=indices.find(key);
        if(found==indices.end()) { const auto index=static_cast<uint32_t>(images.size());images.push_back(fonts.sdf(key.first,key.second));found=indices.emplace(key,index).first; }
        out.integer(found->second);
    }
    out.integer(layout.characters.size());for(const auto& c:layout.characters)
        for(uint32_t field:{c.byteStart,c.byteEnd,c.line,c.word,c.nonSpaceCharacter,c.space?1u:0u}) out.integer(field);
    out.integer(layout.clusters.size());for(const auto& c:layout.clusters)
        for(uint32_t field:{c.byteStart,c.byteEnd,c.line,c.word,c.character,c.nonSpaceCharacter,c.space?1u:0u,c.characterEnd,c.nonSpaceCharacterEnd}) out.integer(field);
    out.integer(images.size());for(const auto& image:images) {
        out.string(image->fontId);out.integer(image->glyphId);out.integer(image->overlaps?1:0);
        for(auto field:{image->width,image->height,image->left,image->top}) out.integer(static_cast<uint32_t>(field));
        out.integer(image->pixels.size());out.bytes.insert(out.bytes.end(),image->pixels.begin(),image->pixels.end());
        if(out.bytes.size()>MaxPreparedPacketBytes) throw std::invalid_argument("Prepared font glyphs exceed 32 MiB; reduce the glyph set");
    }
    return out.bytes;
}
std::vector<std::shared_ptr<const SdfGlyph>> decodeSdfSeeds(const uint8_t* bytes,size_t length) {
    if(!length) return {};
    if(!bytes || length>8*1024*1024) throw std::invalid_argument("Invalid SDF seed packet");
    Reader in{bytes,length};if(in.integer()!=0x46534331) throw std::invalid_argument("Unsupported SDF settings cache");
    std::vector<std::shared_ptr<const SdfGlyph>> images;const auto count=in.count(8192);
    for(uint32_t i=0;i<count;++i) {
        auto image=std::make_shared<SdfGlyph>();image->fontId=in.string();image->glyphId=in.count(UINT32_MAX);
        image->overlaps=in.count(1)!=0;image->width=in.count(2048);image->height=in.count(2048);
        image->left=in.signedInteger();image->top=in.signedInteger();const auto pixels=in.count(2048*2048);
        if(pixels!=static_cast<uint32_t>(image->width*image->height) || pixels>length-in.at) throw std::invalid_argument("Invalid cached SDF pixels");
        image->pixels.assign(bytes+in.at,bytes+in.at+pixels);in.at+=pixels;images.push_back(image);
    }
    if(in.at!=length) throw std::invalid_argument("Trailing SDF cache data");return images;
}

std::shared_ptr<const Layout> decodePreparedText(const uint8_t* bytes,size_t length) {
    if(!bytes || length>MaxPreparedPacketBytes) throw std::invalid_argument("Invalid prepared text packet size");
    Reader in{bytes,length};if(in.integer()!=0x46545031 || in.integer()!=1) throw std::invalid_argument("Unsupported prepared text transport");
    auto out=std::make_shared<Layout>();out->styled=true;out->fontId=in.string();out->upem=in.count(65535);
    if(!out->upem) throw std::invalid_argument("Invalid prepared font em");
    out->ascent=in.signedInteger();out->descent=in.signedInteger();out->lineGap=in.signedInteger();
    out->styledAscent=in.real();out->styledDescent=in.real();out->styledLineGap=in.real();
    const auto fontCount=in.count(512);for(uint32_t i=0;i<fontCount;++i) out->fontIds.push_back(in.string());
    const auto lines=in.count(4097);
    out->lineAdvances.resize(lines);out->styledLineAdvances.resize(lines);out->lineClusterCounts.resize(lines);
    for(uint32_t i=0;i<lines;++i) { out->styledLineAdvances[i]=in.real();out->lineClusterCounts[i]=in.count(4096); }
    const auto glyphs=in.count(MaxPreparedGlyphs);out->glyphs.reserve(glyphs);out->geometry.reserve(glyphs);std::vector<uint32_t> imageIndices;
    if(!lines && glyphs) throw std::invalid_argument("Prepared glyphs have no line");
    for(uint32_t i=0;i<glyphs;++i) {
        Glyph g{};g.id=in.count(65535);g.cluster=in.count(16384);g.line=in.count(lines-1);
        g.xAdvance=in.signedInteger();g.yAdvance=in.signedInteger();g.xOffset=in.signedInteger();g.yOffset=in.signedInteger();
        g.xBearing=in.signedInteger();g.yBearing=in.signedInteger();g.inkWidth=in.signedInteger();g.inkHeight=in.signedInteger();
        if(!fontCount) throw std::invalid_argument("Prepared glyph has no font");
        Layout::Geometry v;v.fontIndex=in.count(fontCount-1);
        double* fields[]={&v.advance,&v.xOffset,&v.yOffset,&v.xBearing,&v.yBearing,&v.inkWidth,&v.inkHeight,&v.scaleX,&v.scaleY,&v.fontSize,&v.baseline,&v.shear,&v.bold};
        for(auto field:fields) *field=in.real();
        if(v.scaleX<0 || v.scaleY<0 || v.fontSize<=0 || v.fontSize>100 || std::abs(v.shear)>1 || v.bold<0 || v.bold>1) throw std::invalid_argument("Invalid prepared glyph style");
        out->glyphs.push_back(g);out->geometry.push_back(v);out->glyphClusters.push_back(in.count(4095));imageIndices.push_back(in.count(MaxPreparedGlyphs-1));
    }
    const auto characters=in.count(4096);
    if(!lines && characters) throw std::invalid_argument("Prepared characters have no line");
    for(uint32_t i=0;i<characters;++i) {
        Layout::Character c{};c.byteStart=in.count(16384);c.byteEnd=in.count(16384);c.line=in.count(lines-1);
        c.word=static_cast<uint32_t>(in.integer());c.nonSpaceCharacter=static_cast<uint32_t>(in.integer());c.space=in.count(1)!=0;
        if(c.byteEnd<=c.byteStart || (i && c.byteStart<out->characters.back().byteEnd) || (c.word!=UINT32_MAX && c.word>=characters) ||
            (c.nonSpaceCharacter!=UINT32_MAX && c.nonSpaceCharacter>=characters)) throw std::invalid_argument("Invalid prepared character membership");
        out->characters.push_back(c);
    }
    const auto clusters=in.count(4096);std::vector<uint32_t> counts(lines);
    if(clusters>glyphs) throw std::invalid_argument("Prepared clusters have no glyphs");
    for(uint32_t i=0;i<clusters;++i) {
        Layout::Cluster c{};c.byteStart=in.count(16384);c.byteEnd=in.count(16384);c.line=in.count(lines-1);c.word=static_cast<uint32_t>(in.integer());
        c.character=in.count(characters);c.nonSpaceCharacter=static_cast<uint32_t>(in.integer());c.space=in.count(1)!=0;c.characterEnd=in.count(characters);c.nonSpaceCharacterEnd=static_cast<uint32_t>(in.integer());
        if(c.byteEnd<=c.byteStart || c.characterEnd<=c.character || c.character>=characters || (c.word!=UINT32_MAX && c.word>=characters) ||
            (c.nonSpaceCharacter!=UINT32_MAX && c.nonSpaceCharacter>=characters) || (c.nonSpaceCharacterEnd!=UINT32_MAX && c.nonSpaceCharacterEnd>characters)) throw std::invalid_argument("Invalid prepared cluster membership");
        out->clusters.push_back(c);++counts[c.line];
    }
    if(counts!=out->lineClusterCounts) throw std::invalid_argument("Prepared line membership count mismatch");
    const auto images=in.count(MaxPreparedGlyphs);std::vector<std::shared_ptr<const SdfGlyph>> pixels;std::map<Key,bool> seen;
    if(images>glyphs) throw std::invalid_argument("Prepared SDF images have no glyphs");
    for(uint32_t i=0;i<images;++i) {
        auto image=std::make_shared<SdfGlyph>();image->fontId=in.string();image->glyphId=in.count(65535);image->overlaps=in.count(1)!=0;
        image->width=static_cast<int>(in.count(2048));image->height=static_cast<int>(in.count(2048));image->left=in.signedInteger();image->top=in.signedInteger();
        const auto count=in.count(2048*2048);
        if(count!=static_cast<uint64_t>(image->width)*image->height || count>length-in.at || !seen.emplace(Key{image->fontId,image->glyphId},true).second ||
            std::abs(static_cast<int64_t>(image->left))>16384 || std::abs(static_cast<int64_t>(image->top))>16384) throw std::invalid_argument("Invalid prepared SDF pixels");
        image->pixels.assign(bytes+in.at,bytes+in.at+count);in.at+=count;pixels.push_back(image);
    }
    if(in.at!=length) throw std::invalid_argument("Trailing prepared text bytes");
    for(uint32_t i=0;i<glyphs;++i) {
        if(out->glyphClusters[i]>=clusters || imageIndices[i]>=images) throw std::invalid_argument("Invalid prepared glyph reference");
        const auto& cluster=out->clusters[out->glyphClusters[i]];const auto& image=pixels[imageIndices[i]];
        if(cluster.line!=out->glyphs[i].line || cluster.byteStart!=out->glyphs[i].cluster || image->fontId!=out->glyphFont(i) || image->glyphId!=out->glyphs[i].id)
            throw std::invalid_argument("Prepared glyph identity mismatch");
        out->externalSdfs.push_back(image);
    }
    return out;
}
}

// ---- TEXT FIX 9c: imported-font tier requests and replies ------------------------------------------------------
namespace fluxx::text {
namespace {
constexpr uint64_t TierRequestMagic=0x46545251, TierReplyMagic=0x46545447;
void writeKey(Writer& out,const TierKey& key) { out.string(key.fontId);out.integer(key.glyphId);out.integer(key.ppem); }
TierKey readKey(Reader& in) {
    TierKey key;key.fontId=in.string();key.glyphId=in.count(UINT32_MAX);key.ppem=in.count(1024);
    if(!tiers::valid(key.ppem) || key.ppem==tiers::BasePpem) throw std::invalid_argument("Invalid tier request ppem");
    return key;
}
void writeKeys(Writer& out,const std::vector<TierKey>& keys) { out.integer(keys.size());for(const auto& key:keys) writeKey(out,key); }
std::vector<TierKey> readKeys(Reader& in) {
    const auto count=in.count(MaxTierRequests);std::vector<TierKey> keys;keys.reserve(count);
    for(uint32_t i=0;i<count;++i) keys.push_back(readKey(in));return keys;
}
}
std::vector<uint8_t> encodeTierRequests(const std::vector<TierKey>& keys) {
    if(keys.size()>MaxTierRequests) throw std::invalid_argument("Too many tier requests");
    Writer out;out.integer(TierRequestMagic);out.integer(1);writeKeys(out,keys);return out.bytes;
}
std::vector<TierKey> decodeTierRequests(const uint8_t* bytes,size_t length) {
    if(!bytes || length<12 || length>1024*1024) throw std::invalid_argument("Invalid tier request packet");
    Reader in{bytes,length};
    if(in.integer()!=TierRequestMagic || in.integer()!=1) throw std::invalid_argument("Unsupported tier request packet");
    auto keys=readKeys(in);if(in.at!=length) throw std::invalid_argument("Trailing tier request data");return keys;
}
std::vector<uint8_t> encodeTierReply(const TierReply& reply) {
    if(reply.glyphs.size()>MaxTierRequests || reply.failed.size()>MaxTierRequests || reply.deferred.size()>MaxTierRequests)
        throw std::invalid_argument("Too many tier glyphs");
    Writer out;out.integer(TierReplyMagic);out.integer(1);out.integer(reply.glyphs.size());
    for(const auto& image:reply.glyphs) {
        out.string(image->fontId);out.integer(image->glyphId);out.integer(image->ppem);out.integer(image->spread);
        for(auto field:{image->width,image->height,image->left,image->top}) out.integer(static_cast<uint32_t>(field));
        out.integer(image->pixels.size());out.bytes.insert(out.bytes.end(),image->pixels.begin(),image->pixels.end());
        if(out.bytes.size()>MaxTierReplyBytes) throw std::invalid_argument("Tier reply exceeds 8 MiB");
    }
    writeKeys(out,reply.failed);writeKeys(out,reply.deferred);return out.bytes;
}
TierReply decodeTierReply(const uint8_t* bytes,size_t length) {
    if(!bytes || length<20 || length>MaxTierReplyBytes) throw std::invalid_argument("Invalid tier reply size");
    Reader in{bytes,length};
    if(in.integer()!=TierReplyMagic || in.integer()!=1) throw std::invalid_argument("Unsupported tier reply");
    TierReply reply;const auto count=in.count(MaxTierRequests);
    for(uint32_t i=0;i<count;++i) {
        auto image=std::make_shared<SdfGlyph>();image->fontId=in.string();image->glyphId=in.count(UINT32_MAX);
        image->ppem=in.count(1024);image->spread=in.count(tiers::MaxSpread);image->overlaps=true;
        if(!tiers::valid(image->ppem) || image->ppem==tiers::BasePpem || image->spread!=tiers::spread(image->ppem))
            throw std::invalid_argument("Invalid tier glyph ppem or spread");
        image->width=static_cast<int>(in.count(2048));image->height=static_cast<int>(in.count(2048));
        image->left=in.signedInteger();image->top=in.signedInteger();const auto pixels=in.count(2048*2048);
        if(pixels!=static_cast<uint64_t>(image->width)*image->height || pixels>length-in.at ||
            std::abs(static_cast<int64_t>(image->left))>16384 || std::abs(static_cast<int64_t>(image->top))>16384)
            throw std::invalid_argument("Invalid tier glyph pixels");
        image->pixels.assign(bytes+in.at,bytes+in.at+pixels);in.at+=pixels;reply.glyphs.push_back(image);
    }
    reply.failed=readKeys(in);reply.deferred=readKeys(in);
    if(in.at!=length) throw std::invalid_argument("Trailing tier reply data");
    return reply;
}
TierReply rasteriseTiers(FontManager& fonts,const std::vector<TierKey>& keys,size_t byteBudget,double secondsBudget) {
    if(keys.size()>MaxTierRequests) throw std::invalid_argument("Too many tier requests");
    TierReply reply;size_t bytes=64;const auto start=std::chrono::steady_clock::now();
    for(const auto& key:keys) {
        const double elapsed=std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count();
        if(elapsed>secondsBudget) { reply.deferred.push_back(key);continue; }
        if(!fonts.contains(key.fontId)) { reply.failed.push_back(key);continue; } // Never a fallback face.
        std::shared_ptr<const SdfGlyph> image;
        try { image=fonts.sdf(key.fontId,key.glyphId,true,key.ppem); }
        catch(const std::exception&) { reply.failed.push_back(key);continue; }
        const size_t record=key.fontId.size()+40+image->pixels.size();
        // Keep room for the failed/deferred lists (<= 2 x 256 keys).
        if(bytes+record+2*MaxTierRequests*(1024+12)>byteBudget && !reply.glyphs.empty()) { reply.deferred.push_back(key);continue; }
        if(bytes+record>byteBudget) { reply.failed.push_back(key);continue; } // A single glyph larger than the reply.
        bytes+=record;reply.glyphs.push_back(image);
    }
    return reply;
}
}
