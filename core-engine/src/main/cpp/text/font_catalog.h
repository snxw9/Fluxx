#pragma once
#include "font_manager.h"
#include <android/asset_manager.h>

namespace fluxx::text {
// Shared CPU catalog; call only on a worker. GPU state never enters this catalog.
std::shared_ptr<FontManager> acquireFontCatalog(AAssetManager* assets);
// Bundled fonts again, with their own FreeType library and lock, for higher SDF atlas tiers (sdf_tiers.h).
// Rasterised off the render thread; never contended with the base catalog.
std::shared_ptr<FontManager> acquireTierCatalog(AAssetManager* assets,size_t budget);
}
