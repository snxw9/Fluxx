#include <jni.h>
#include <android/hardware_buffer_jni.h>
#include <android/asset_manager_jni.h>
#include <android/native_window_jni.h>
#include <android/bitmap.h>
#include <cstring>
#include <algorithm>
#include "engine_core.h"

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
JNIEXPORT void JNICALL Java_com_fluxx_android_engine_RenderBridge_destroy(JNIEnv*,jobject,jlong session) { delete reinterpret_cast<VulkanRenderer*>(session); }
JNIEXPORT void JNICALL Java_com_fluxx_android_engine_RenderBridge_resize(JNIEnv*,jobject,jlong session,jint w,jint h) { reinterpret_cast<VulkanRenderer*>(session)->resize(w,h); }
JNIEXPORT jboolean JNICALL Java_com_fluxx_android_engine_RenderBridge_begin(JNIEnv*,jobject,jlong session,jint w,jint h,jint pw,jint ph) { return reinterpret_cast<VulkanRenderer*>(session)->beginFrame(w,h,pw,ph); }
JNIEXPORT jboolean JNICALL Java_com_fluxx_android_engine_RenderBridge_layer(JNIEnv* env,jobject,jlong session,jlong slot,jobject buffer,jfloatArray matrix,jfloat opacity) {
    if(!buffer || env->GetArrayLength(matrix)!=16) return false;
    float m[16]; env->GetFloatArrayRegion(matrix,0,16,m);
    return reinterpret_cast<VulkanRenderer*>(session)->setFrameLayer(slot,AHardwareBuffer_fromHardwareBuffer(env,buffer),m,opacity);
}
JNIEXPORT jboolean JNICALL Java_com_fluxx_android_engine_RenderBridge_flush(JNIEnv*,jobject,jlong session) { return reinterpret_cast<VulkanRenderer*>(session)->flushBatch(); }
JNIEXPORT jboolean JNICALL Java_com_fluxx_android_engine_RenderBridge_finish(JNIEnv*,jobject,jlong session) { return reinterpret_cast<VulkanRenderer*>(session)->finishFrame(); }
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
