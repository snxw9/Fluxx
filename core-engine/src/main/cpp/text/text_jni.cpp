#include "font_manager.h"
#include "font_catalog.h"
#ifdef FLUXX_TEXT_DIAGNOSTICS
#include "text_diagnostics.h"
#endif
#include <jni.h>
#include <android/asset_manager_jni.h>
#include <map>
#include <mutex>
#include <stdexcept>

namespace {
using fluxx::text::FontManager;
std::mutex registryMutex;
std::map<jlong, std::shared_ptr<FontManager>> sessions;
std::weak_ptr<FontManager> catalog;
jlong nextSession = 1;
void fail(JNIEnv* env, const std::exception& error) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
std::shared_ptr<FontManager> get(jlong id) {
    std::lock_guard<std::mutex> lock(registryMutex);
    const auto found = sessions.find(id);
    if (found == sessions.end()) throw std::invalid_argument("Font session is closed");
    return found->second;
}
std::string string(JNIEnv* env, jstring value) {
    if (!value || env->GetStringLength(value) > 4096) throw std::invalid_argument("Invalid string argument");
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::bad_alloc();
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
std::vector<uint8_t> asset(AAssetManager* manager, const char* name) {
    std::unique_ptr<AAsset, decltype(&AAsset_close)> input(AAssetManager_open(manager, name, AASSET_MODE_BUFFER), AAsset_close);
    if (!input) throw std::runtime_error(std::string("Missing bundled font: ") + name);
    const auto length = AAsset_getLength64(input.get());
    if (length <= 0 || length > 16 * 1024 * 1024) throw std::runtime_error("Invalid font asset length");
    std::vector<uint8_t> bytes(static_cast<size_t>(length));
    size_t at = 0;
    while (at < bytes.size()) {
        const int count = AAsset_read(input.get(), bytes.data() + at, bytes.size() - at);
        if (count <= 0) throw std::runtime_error("Incomplete font asset read");
        at += static_cast<size_t>(count);
    }
    return bytes;
}
}

std::shared_ptr<FontManager> fluxx::text::acquireFontCatalog(AAssetManager* source) {
    std::lock_guard<std::mutex> lock(registryMutex);
    if (!source) throw std::invalid_argument("AssetManager is null");
    auto manager = catalog.lock();
    if (!manager) {
        std::vector<FontInput> fonts;
        fonts.push_back({"fluxx.sans", asset(source, "fonts/Inter-Regular.ttf")});
        fonts.push_back({"fluxx.serif", asset(source, "fonts/NotoSerif-Regular.ttf")});
        fonts.push_back({"fluxx.mono", asset(source, "fonts/JetBrainsMono-Regular.ttf")});
        manager = std::make_shared<FontManager>(std::move(fonts));
        catalog = manager;
    }
    return manager;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_fluxx_android_engine_TextNative_create(JNIEnv* env, jobject, jobject assets) {
    try {
        auto manager = fluxx::text::acquireFontCatalog(AAssetManager_fromJava(env, assets));
        std::lock_guard<std::mutex> lock(registryMutex);
        if (nextSession == INT64_MAX) throw std::runtime_error("Font session IDs exhausted");
        const jlong id = nextSession++; sessions.emplace(id, manager); return id;
    } catch (const std::exception& error) { fail(env, error); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_com_fluxx_android_engine_TextNative_close(JNIEnv*, jobject, jlong id) {
    std::shared_ptr<FontManager> released;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        auto found = sessions.find(id);
        if (found != sessions.end()) { released = std::move(found->second); sessions.erase(found); }
    }
    // Last lease destroys handles/bytes after in-flight calls, outside the registry lock.
}
extern "C" JNIEXPORT jstring JNICALL Java_com_fluxx_android_engine_TextNative_shape(JNIEnv* env, jobject, jlong id, jstring font, jbyteArray utf8) {
    try {
        if (!utf8 || env->GetArrayLength(utf8) > static_cast<jsize>(FontManager::MaxTextBytes)) throw std::invalid_argument("Invalid text bytes");
        std::string text(static_cast<size_t>(env->GetArrayLength(utf8)), '\0');
        env->GetByteArrayRegion(utf8, 0, static_cast<jsize>(text.size()), reinterpret_cast<jbyte*>(text.data()));
        if (env->ExceptionCheck()) return nullptr;
        const auto layout = get(id)->shape(string(env, font), text);
        const auto json = fluxx::text::layoutJson(*layout);
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception& error) { fail(env, error); return nullptr; }
}
#ifdef FLUXX_TEXT_DIAGNOSTICS
extern "C" JNIEXPORT void JNICALL Java_com_fluxx_android_engine_TextDebugBridge_dump(JNIEnv* env, jobject, jlong id, jstring directory) {
    try { fluxx::text::dumpDiagnostics(*get(id), string(env, directory)); }
    catch (const std::exception& error) { fail(env, error); }
}
#endif
