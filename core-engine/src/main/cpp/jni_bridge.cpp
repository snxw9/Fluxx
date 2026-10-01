#include <jni.h>
#include <android/hardware_buffer_jni.h>
#include <android/asset_manager_jni.h>
#include <android/native_window_jni.h>
#include <android/bitmap.h>
#include <cstring>
#include <algorithm>
#include <stdexcept>
#include "engine_core.h"
#include "gpu_ledger.h"

extern "C" {
JNIEXPORT jint JNICALL Java_com_fluxx_android_engine_FluxxEngine_nativePing(JNIEnv*, jobject, jint value) { return value + 1; }
JNIEXPORT jboolean JNICALL Java_com_fluxx_android_engine_FluxxEngine_nativeInit(JNIEnv* env, jobject, jobject surface, jint w, jint h, jobject assets) {
    if (!surface) return false;
    return EngineCore::getInstance()->init(ANativeWindow_fromSurface(env, surface), w, h, AAssetManager_fromJava(env, assets));
}
JNIEXPORT void JNICALL Java_com_fluxx_android_engine_FluxxEngine_nativeCleanup(JNIEnv*, jobject) { EngineCore::getInstance()->release(); }
JNIEXPORT void JNICALL Java_com_fluxx_android_engine_FluxxEngine_resize(JNIEnv*, jobject, jint w, jint h) { EngineCore::getInstance()->resize(w,h); }
JNIEXPORT jboolean JNICALL Java_com_fluxx_android_engine_FluxxEngine_beginFrame(JNIEnv*, jobject, jint w, jint h) { return EngineCore::getInstance()->renderer().beginFrame(w,h); }
JNIEXPORT jboolean JNICALL Java_com_fluxx_android_engine_FluxxEngine_finishFrame(JNIEnv*, jobject) { return EngineCore::getInstance()->renderer().finishFrame(); }
JNIEXPORT void JNICALL Java_com_fluxx_android_engine_FluxxEngine_releaseLayers(JNIEnv*, jobject) { EngineCore::getInstance()->renderer().releaseLayers(); }
JNIEXPORT jboolean JNICALL Java_com_fluxx_android_engine_FluxxEngine_setFrameLayer(JNIEnv* env, jobject, jlong id, jobject hardwareBuffer, jfloatArray matrix, jfloat opacity) {
    if (!hardwareBuffer || env->GetArrayLength(matrix) != 16) return false;
    float m[16]; env->GetFloatArrayRegion(matrix,0,16,m);
    return EngineCore::getInstance()->renderer().setFrameLayer(id, AHardwareBuffer_fromHardwareBuffer(env,hardwareBuffer),m,opacity);
}
// Upload static pixels once, using a GPU-sampleable allocation. Bitmap pixels are premultiplied;
// the existing Vulkan fragment/blend contract expects straight alpha.
JNIEXPORT jobject JNICALL Java_com_fluxx_android_engine_FluxxEngine_bitmapBuffer(JNIEnv* env, jobject, jobject bitmap) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env,bitmap,&info) != ANDROID_BITMAP_RESULT_SUCCESS || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return nullptr;
    AHardwareBuffer_Desc desc{}; desc.width=info.width; desc.height=info.height; desc.layers=1;
    desc.format=AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage=AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY;
    AHardwareBuffer* buffer=nullptr;
    if (AHardwareBuffer_allocate(&desc,&buffer) != 0) return nullptr;
    void* src=nullptr; void* dst=nullptr;
    if (AndroidBitmap_lockPixels(env,bitmap,&src) != ANDROID_BITMAP_RESULT_SUCCESS) { AHardwareBuffer_release(buffer); return nullptr; }
    if (AHardwareBuffer_lock(buffer,AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY,-1,nullptr,&dst) != 0) {
        AndroidBitmap_unlockPixels(env,bitmap); AHardwareBuffer_release(buffer); return nullptr;
    }
    AHardwareBuffer_describe(buffer,&desc);
    for (uint32_t y=0;y<info.height;y++) {
        auto* s=static_cast<uint8_t*>(src)+y*info.stride;
        auto* d=static_cast<uint8_t*>(dst)+y*desc.stride*4;
        for(uint32_t x=0;x<info.width;x++) {
            uint32_t a=s[x*4+3]; d[x*4+3]=a;
            for(int k=0;k<3;k++) d[x*4+k]= a ? static_cast<uint8_t>(std::min(255u,(s[x*4+k]*255u+a/2)/a)) : 0;
        }
    }
    AHardwareBuffer_unlock(buffer,nullptr); AndroidBitmap_unlockPixels(env,bitmap);
    jobject result=AHardwareBuffer_toHardwareBuffer(env,buffer); AHardwareBuffer_release(buffer); return result;
}
}

extern "C" {
JNIEXPORT jlong JNICALL Java_com_fluxx_android_engine_RenderBridge_create(JNIEnv* env,jobject,jobject surface,jint width,jint height,jobject assets) {
    auto* renderer=new VulkanRenderer();
    auto* window=surface ? ANativeWindow_fromSurface(env,surface) : nullptr;
    if (!renderer->init(window,AAssetManager_fromJava(env,assets))) { delete renderer; return 0; }
    if(window) renderer->resize(width,height);
    return reinterpret_cast<jlong>(renderer);
}
// Standard UTF-8, including supplementary characters; JNI modified UTF-8 is not text input.
static std::string textUtf8(JNIEnv* env,jstring value) {
    if(!value) throw std::invalid_argument("Null text");
    const jsize length=env->GetStringLength(value);
    if(length>16384) throw std::invalid_argument("Text input too large");
    const jchar* chars=env->GetStringChars(value,nullptr);
    if(!chars) throw std::bad_alloc();
    std::string output;
    try {
        for(jsize i=0;i<length;++i) {
            uint32_t cp=chars[i];
            if(cp>=0xD800 && cp<=0xDBFF) {
                if(i+1>=length || chars[i+1]<0xDC00 || chars[i+1]>0xDFFF) throw std::invalid_argument("Unpaired UTF-16 surrogate");
                cp=0x10000+((cp-0xD800)<<10)+(chars[++i]-0xDC00);
            } else if(cp>=0xDC00 && cp<=0xDFFF) throw std::invalid_argument("Unpaired UTF-16 surrogate");
            if(cp<0x80) output.push_back(static_cast<char>(cp));
            else if(cp<0x800) { output.push_back(static_cast<char>(0xC0|(cp>>6))); output.push_back(static_cast<char>(0x80|(cp&63))); }
            else if(cp<0x10000) { output.push_back(static_cast<char>(0xE0|(cp>>12))); output.push_back(static_cast<char>(0x80|((cp>>6)&63))); output.push_back(static_cast<char>(0x80|(cp&63))); }
            else { output.push_back(static_cast<char>(0xF0|(cp>>18))); output.push_back(static_cast<char>(0x80|((cp>>12)&63))); output.push_back(static_cast<char>(0x80|((cp>>6)&63))); output.push_back(static_cast<char>(0x80|(cp&63))); }
        }
        if(output.size()>16384) throw std::invalid_argument("Text exceeds UTF-8 byte limit");
    } catch(...) { env->ReleaseStringChars(value,chars); throw; }
    env->ReleaseStringChars(value,chars); return output;
}
static void textFailure(JNIEnv* env,const std::exception& error) {
    if(env->ExceptionCheck()) return;
    const char* type=dynamic_cast<const fluxx::text::TextCapacityError*>(&error)?
        "com/fluxx/android/engine/TextCapacityException":"java/lang/IllegalStateException";
    env->ThrowNew(env->FindClass(type),error.what());
}
JNIEXPORT jlong JNICALL Java_com_fluxx_android_engine_RenderBridge_upsertText(JNIEnv* env,jobject,jlong session,jstring font,jstring text) {
    try { return reinterpret_cast<VulkanRenderer*>(session)->upsertText(textUtf8(env,font),textUtf8(env,text)); }
    catch(const std::exception& error) { textFailure(env,error); return 0; }
}
JNIEXPORT void JNICALL Java_com_fluxx_android_engine_RenderBridge_releaseText(JNIEnv*,jobject,jlong session,jlong handle) {
    reinterpret_cast<VulkanRenderer*>(session)->releaseText(handle);
}
JNIEXPORT jdoubleArray JNICALL Java_com_fluxx_android_engine_RenderBridge_textMetrics(JNIEnv* env,jobject,jlong session,jlong handle) {
    try {
        const auto values=reinterpret_cast<VulkanRenderer*>(session)->textMetrics(handle);
        auto result=env->NewDoubleArray(static_cast<jsize>(values.size()));
        if(result) env->SetDoubleArrayRegion(result,0,static_cast<jsize>(values.size()),values.data());
        return result;
    } catch(const std::exception& error) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),error.what()); return nullptr; }
}
JNIEXPORT jboolean JNICALL Java_com_fluxx_android_engine_RenderBridge_prepareText(JNIEnv* env,jobject,jlong session,jlongArray handles,jboolean proofOnly) {
    try {
        const auto count=env->GetArrayLength(handles); std::vector<jlong> input(count);
        env->GetLongArrayRegion(handles,0,count,input.data()); if(env->ExceptionCheck()) return false;
        return reinterpret_cast<VulkanRenderer*>(session)->prepareText(std::vector<int64_t>(input.begin(),input.end()),proofOnly);
    } catch(const std::exception& error) { textFailure(env,error); return false; }
}
JNIEXPORT void JNICALL Java_com_fluxx_android_engine_RenderBridge_configureText(JNIEnv*,jobject,jlong session,jint pages) {
    reinterpret_cast<VulkanRenderer*>(session)->configureText(pages==2?2:4);
}
JNIEXPORT jboolean JNICALL Java_com_fluxx_android_engine_RenderBridge_textLayer(JNIEnv* env,jobject,jlong session,jlong handle,jfloatArray matrix,
    jfloat size,jint alignment,jint argb,jfloat opacity) {
    try {
        if(!matrix || env->GetArrayLength(matrix)!=16) return false;
        float m[16]; env->GetFloatArrayRegion(matrix,0,16,m); if(env->ExceptionCheck()) return false;
        return reinterpret_cast<VulkanRenderer*>(session)->setTextLayer(handle,m,size,alignment,static_cast<uint32_t>(argb),opacity);
    } catch(const std::exception& error) { textFailure(env,error); return false; }
}
JNIEXPORT jstring JNICALL Java_com_fluxx_android_engine_RenderBridge_textStats(JNIEnv* env,jobject,jlong session) {
    const auto stats=reinterpret_cast<VulkanRenderer*>(session)->textStatsJson(); return env->NewStringUTF(stats.c_str());
}
#ifdef FLUXX_TEXT_DIAGNOSTICS
JNIEXPORT jboolean JNICALL Java_com_fluxx_android_engine_TextDebugBridge_surface(JNIEnv* env,jobject,jlong session,jobject surface,jint w,jint h) {
    return reinterpret_cast<VulkanRenderer*>(session)->proofSurface(surface?ANativeWindow_fromSurface(env,surface):nullptr,w,h);
}
JNIEXPORT void JNICALL Java_com_fluxx_android_engine_TextDebugBridge_repack(JNIEnv* env,jobject,jlong session) {
    try { reinterpret_cast<VulkanRenderer*>(session)->proofRepack(); }
    catch(const std::exception& error) { textFailure(env,error); }
}
JNIEXPORT jstring JNICALL Java_com_fluxx_android_engine_TextDebugBridge_gpuLedger(JNIEnv* env,jobject) {
    const auto json=fluxx::debug::ledger.json(); return env->NewStringUTF(json.c_str());
}
JNIEXPORT jboolean JNICALL Java_com_fluxx_android_engine_TextDebugBridge_pixels(JNIEnv* env,jobject,jlong session,jobject output) {
    auto* bytes=static_cast<uint8_t*>(env->GetDirectBufferAddress(output));
    const auto capacity=env->GetDirectBufferCapacity(output);
    return capacity>=0 && reinterpret_cast<VulkanRenderer*>(session)->proofPixels(bytes,static_cast<size_t>(capacity));
}
#endif
JNIEXPORT void JNICALL Java_com_fluxx_android_engine_RenderBridge_destroy(JNIEnv*,jobject,jlong session) { delete reinterpret_cast<VulkanRenderer*>(session); }
JNIEXPORT void JNICALL Java_com_fluxx_android_engine_RenderBridge_resize(JNIEnv*,jobject,jlong session,jint w,jint h) { reinterpret_cast<VulkanRenderer*>(session)->resize(w,h); }
JNIEXPORT jboolean JNICALL Java_com_fluxx_android_engine_RenderBridge_begin(JNIEnv*,jobject,jlong session,jint w,jint h,jint pw,jint ph) { return reinterpret_cast<VulkanRenderer*>(session)->beginFrame(w,h,pw,ph); }
JNIEXPORT jboolean JNICALL Java_com_fluxx_android_engine_RenderBridge_layer(JNIEnv* env,jobject,jlong session,jlong slot,jobject buffer,jfloatArray matrix,jfloat opacity) {
    if(!buffer || env->GetArrayLength(matrix)!=16) return false;
    float m[16]; env->GetFloatArrayRegion(matrix,0,16,m);
    return reinterpret_cast<VulkanRenderer*>(session)->setFrameLayer(slot,AHardwareBuffer_fromHardwareBuffer(env,buffer),m,opacity);
}
JNIEXPORT jboolean JNICALL Java_com_fluxx_android_engine_RenderBridge_flush(JNIEnv* env,jobject,jlong session) {
    try { return reinterpret_cast<VulkanRenderer*>(session)->flushBatch(); }
    catch(const std::exception& error) { textFailure(env,error); return false; }
}
JNIEXPORT jboolean JNICALL Java_com_fluxx_android_engine_RenderBridge_finish(JNIEnv* env,jobject,jlong session) {
    try { return reinterpret_cast<VulkanRenderer*>(session)->finishFrame(); }
    catch(const std::exception& error) { textFailure(env,error); return false; }
}
JNIEXPORT jlong JNICALL Java_com_fluxx_android_engine_RenderBridge_readPreviewPixel(JNIEnv*,jobject,jlong session,jfloat x,jfloat y) {
    return session ? reinterpret_cast<VulkanRenderer*>(session)->readPreviewPixel(x,y) : -1;
}
JNIEXPORT jboolean JNICALL Java_com_fluxx_android_engine_RenderBridge_copyYuv(JNIEnv* env,jobject,jlong session,jobject y,jobject u,jobject v,jint yr,jint ur,jint vr,jint up,jint vp) {
    auto* r=reinterpret_cast<VulkanRenderer*>(session);
    auto* yp=static_cast<uint8_t*>(env->GetDirectBufferAddress(y));
    auto* uptr=static_cast<uint8_t*>(env->GetDirectBufferAddress(u));
    auto* vptr=static_cast<uint8_t*>(env->GetDirectBufferAddress(v));
    const int w=r->getCompWidth(),h=r->getCompHeight();
    if(!yp || !uptr || !vptr || yr<w || up<1 || vp<1 || ur<1 || vr<1) return false;
    if(env->GetDirectBufferCapacity(y)<static_cast<jlong>(h-1)*yr+w ||
        env->GetDirectBufferCapacity(u)<static_cast<jlong>(h/2-1)*ur+(w/2-1)*up+1 ||
        env->GetDirectBufferCapacity(v)<static_cast<jlong>(h/2-1)*vr+(w/2-1)*vp+1) return false;
    return r->copyYuv(yp,uptr,vptr,yr,ur,vr,up,vp);
}
}
