#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

// Coverage-correct SDF generation (TEXT FIX 9b).
//
// FreeType 2.14's SDF renderer, even with the "overlaps" property on, inverts the sign along the junctions of
// overlapping contours (variable fonts keep them; their generated static instances often do too). Glyphs then show
// holes and broken strokes. The fix: after FreeType renders a glyph's SDF, self-check its inside/outside sign against
// a supersampled non-zero-winding coverage mask of the same outline. Only glyphs that fail are rebuilt from the mask
// with an exact Euclidean distance transform, on FreeType's own grid and with its byte mapping. Glyphs that pass,
// including every bundled-font glyph checked so far, keep FreeType's bytes unchanged.
namespace fluxx::text::coverage_sdf {

// Bumped whenever SDF bytes can change for the same font/glyph/tier: persisted caches must not reuse older bitmaps.
constexpr unsigned GeneratorVersion = 2;
// Supersampling bound per glyph: k in 1..MaxSupersample, (width*k) * (height*k) <= MaxMaskPixels.
constexpr unsigned MaxSupersample = 4;
constexpr std::size_t MaxMaskPixels = std::size_t{1} << 20;

// Largest k (1..4) keeping the supersampled mask within MaxMaskPixels.
unsigned supersample(int width, int height);

// FreeType's map_fixed_to_sdf: distance in pixels (positive inside), 128 = edge, clamped to the spread.
uint8_t encode(double distancePixels, unsigned spread);

struct Check { int holes = 0; int spills = 0; bool failed() const { return holes + spills > 0; } };

// mask: (width*k) x (height*k) bytes, rows top-down, nonzero = inside (non-zero winding fill).
// A hole is a pixel whose coverage is >= 7/8 but whose SDF says outside; a spill has zero coverage but says inside.
Check compare(const std::vector<uint8_t>& sdf, const std::vector<uint8_t>& mask, int width, int height, unsigned k);

// Builds an R8 SDF (width x height, FreeType byte mapping) from the supersampled mask.
std::vector<uint8_t> fromMask(const std::vector<uint8_t>& mask, int width, int height, unsigned k, unsigned spread);

}
