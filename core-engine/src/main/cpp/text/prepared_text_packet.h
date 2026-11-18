#pragma once
#include "font_manager.h"
#include <tuple>

namespace fluxx::text {
// Preparation-only transport. Not a project schema or a frame/draw ABI.
constexpr size_t MaxPreparedPacketBytes=32*1024*1024;
constexpr uint32_t MaxPreparedGlyphs=16384;
std::vector<uint8_t> encodePreparedText(const Layout& layout,FontManager& fonts);
std::vector<std::shared_ptr<const SdfGlyph>> decodeSdfSeeds(const uint8_t* bytes,size_t length);
std::shared_ptr<const Layout> decodePreparedText(const uint8_t* bytes,size_t length);

// TEXT FIX 9c: imported-font atlas tiers. The renderer asks for (font, glyph, ppem) keys it cannot rasterise itself
// (imported faces live only in the isolated worker); the worker answers with tier SDFs, plus keys that failed and keys
// deferred to a later call. Bounds: <= MaxTierRequests keys per call, <= MaxTierReplyBytes per reply.
struct TierKey {
    std::string fontId; uint32_t glyphId=0; unsigned ppem=0;
    bool operator<(const TierKey& o) const { return std::tie(fontId,glyphId,ppem)<std::tie(o.fontId,o.glyphId,o.ppem); }
    bool operator==(const TierKey& o) const { return fontId==o.fontId && glyphId==o.glyphId && ppem==o.ppem; }
};
struct TierReply {
    std::vector<std::shared_ptr<const SdfGlyph>> glyphs;
    std::vector<TierKey> failed, deferred;
};
constexpr uint32_t MaxTierRequests=256;
constexpr size_t MaxTierReplyBytes=8*1024*1024;
std::vector<uint8_t> encodeTierRequests(const std::vector<TierKey>& keys);
std::vector<TierKey> decodeTierRequests(const uint8_t* bytes,size_t length);
std::vector<uint8_t> encodeTierReply(const TierReply& reply);
TierReply decodeTierReply(const uint8_t* bytes,size_t length);
// Worker side: rasterise requests until the byte or time budget is spent; the rest is deferred (never dropped).
TierReply rasteriseTiers(FontManager& fonts,const std::vector<TierKey>& keys,size_t byteBudget=MaxTierReplyBytes,double secondsBudget=20.0);
}
