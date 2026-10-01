#pragma once
#include "font_manager.h"
#include <android/asset_manager.h>

namespace fluxx::text {
// Shared CPU catalog; call only on a worker. GPU state never enters this catalog.
std::shared_ptr<FontManager> acquireFontCatalog(AAssetManager* assets);
}
