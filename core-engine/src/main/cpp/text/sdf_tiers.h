#pragma once
#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>

// SDF atlas resolution tiers. Mirrors com.fluxx.android.render.SdfAtlasTiers (the Kotlin policy that selects a
// tier per layer and is covered by JVM tests); keep the constants and rules identical.
namespace fluxx::text::tiers {
constexpr std::array<unsigned,4> Ppem{48,96,192,384};
constexpr unsigned BasePpem=48, BaseSpread=8;
// FreeType's "sdf" module accepts spreads of 2..32 pixels.
constexpr unsigned MaxSpread=32;
// Spread scales with the tier (8, 16, 32) until FreeType's limit; 384 ppem keeps 32 (half the relative range).
constexpr unsigned spread(unsigned ppem) { return std::min(BaseSpread*ppem/BasePpem,MaxSpread); }
constexpr bool valid(unsigned ppem) { for(auto p:Ppem) if(p==ppem) return true; return false; }
constexpr unsigned lower(unsigned ppem) { unsigned result=BasePpem; for(auto p:Ppem) if(p<ppem) result=p; return result; }
// Distances (half stroke, softness, faux bold) are encoded for the base tier: ppem / (2 * spread * size).
// A tier with a different spread/ppem ratio scales them by this factor so edges match across tiers.
constexpr float distanceScale(unsigned ppem) {
    return (static_cast<float>(BaseSpread)/BasePpem)/(static_cast<float>(spread(ppem))/static_cast<float>(ppem));
}
// Highest tier <= wanted whose glyphs are all ready; the base tier is always available (generated inline).
template<class Ready> unsigned resolve(unsigned wanted,Ready ready) {
    for(auto it=Ppem.rbegin();it!=Ppem.rend();++it) if(*it<=wanted && (*it==BasePpem || ready(*it))) return *it;
    return BasePpem;
}
// Eviction order under the memory cap: highest tier first, then least recently used.
struct Use { unsigned ppem; uint64_t lastUse; };
inline bool evictBefore(const Use& a,const Use& b) { return a.ppem!=b.ppem?a.ppem>b.ppem:a.lastUse<b.lastUse; }
// CPU cache for generated tier glyphs (in addition to the device atlas pages).
constexpr size_t CpuBudget=16u*1024*1024, CpuBudgetLowRam=8u*1024*1024;
}
