#ifdef FLUXX_TEXT_DIAGNOSTICS
#include "text/allocation_probe.h"
#include "text/prepared_text_packet.h"
#endif
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
#include "text/prepared_text_packet.h"

namespace {
struct CharacterBufferPin {
    JavaVM* vm=nullptr;jobject modifiers=nullptr,codes=nullptr;
    CharacterBufferPin(JNIEnv* env,jobject m,jobject c) {
        if(env->GetJavaVM(&vm)!=JNI_OK || !vm) throw std::runtime_error("Cannot pin character buffers");
        modifiers=env->NewGlobalRef(m);codes=env->NewGlobalRef(c);
    }
    ~CharacterBufferPin() {
        JNIEnv* env=nullptr;bool attached=false;
        if(vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)==JNI_EDETACHED) {
            if(vm->AttachCurrentThread(&env,nullptr)!=JNI_OK) return;
            attached=true;
        }
        if(env) { if(modifiers) env->DeleteGlobalRef(modifiers);if(codes) env->DeleteGlobalRef(codes); }
        if(attached) vm->DetachCurrentThread();
    }
};
}

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
JNIEXPORT jlong JNICALL Java_com_fluxx_android_engine_RenderBridge_upsertText(JNIEnv* env,jobject,jlong session,jstring font,jstring text,jboolean ligatures) {
    try { return reinterpret_cast<VulkanRenderer*>(session)->upsertText(textUtf8(env,font),textUtf8(env,text),ligatures); }
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
        const auto count=env->GetArrayLength(handles);
        auto* input=env->GetLongArrayElements(handles,nullptr); if(!input) return false;
        bool result=false;
        try { result=reinterpret_cast<VulkanRenderer*>(session)->prepareText(input,static_cast<size_t>(count),proofOnly); }
        catch(...) { env->ReleaseLongArrayElements(handles,input,JNI_ABORT); throw; }
        env->ReleaseLongArrayElements(handles,input,JNI_ABORT); return result;
    } catch(const std::exception& error) { textFailure(env,error); return false; }
}
JNIEXPORT void JNICALL Java_com_fluxx_android_engine_RenderBridge_configureText(JNIEnv*,jobject,jlong session,jint pages) {
    // Atlas page cap (2048x2048 R8 = 4 MiB each), shared by every SDF tier: 3 pages on low-RAM devices, 6 otherwise.
    reinterpret_cast<VulkanRenderer*>(session)->configureText(static_cast<unsigned>(std::clamp<jint>(pages,1,8)));
}
JNIEXPORT jboolean JNICALL Java_com_fluxx_android_engine_RenderBridge_textTiers(JNIEnv* env,jobject,jlong session,jlongArray handles,jintArray ppems,jboolean synchronous) {
    try {
        const auto count=env->GetArrayLength(handles);
        if(count!=env->GetArrayLength(ppems)) throw std::invalid_argument("Tier arrays differ in length");
        std::vector<jlong> h(static_cast<size_t>(count));std::vector<jint> p(static_cast<size_t>(count));
        env->GetLongArrayRegion(handles,0,count,h.data());env->GetIntArrayRegion(ppems,0,count,p.data());
        if(env->ExceptionCheck()) return false;
        std::vector<int64_t> handleValues(h.begin(),h.end());std::vector<int32_t> ppemValues(p.begin(),p.end());
        return reinterpret_cast<VulkanRenderer*>(session)->setTextTiers(handleValues.data(),ppemValues.data(),handleValues.size(),synchronous);
    } catch(const std::exception& error) { textFailure(env,error); return false; }
}
JNIEXPORT jint JNICALL Java_com_fluxx_android_engine_RenderBridge_awaitTextTiers(JNIEnv*,jobject,jlong session,jint timeoutMs) {
    return static_cast<jint>(reinterpret_cast<VulkanRenderer*>(session)->awaitTextTiers(timeoutMs));
}
JNIEXPORT jbyteArray JNICALL Java_com_fluxx_android_engine_RenderBridge_textTierRequests(JNIEnv* env,jobject,jlong session) {
    try {
        const auto bytes=reinterpret_cast<VulkanRenderer*>(session)->takeTextTierRequests();
        const auto result=env->NewByteArray(static_cast<jsize>(bytes.size()));
        if(result && !bytes.empty()) env->SetByteArrayRegion(result,0,static_cast<jsize>(bytes.size()),reinterpret_cast<const jbyte*>(bytes.data()));
        return result;
    } catch(const std::exception& error) { textFailure(env,error); return nullptr; }
}
JNIEXPORT jint JNICALL Java_com_fluxx_android_engine_RenderBridge_supplyTextTierGlyphs(JNIEnv* env,jobject,jlong session,jbyteArray reply) {
    try {
        if(!reply) throw std::invalid_argument("Missing tier reply");
        const auto length=env->GetArrayLength(reply);
        if(length<=0 || static_cast<size_t>(length)>fluxx::text::MaxTierReplyBytes) throw std::invalid_argument("Invalid tier reply size");
        std::vector<uint8_t> buffer(static_cast<size_t>(length));
        env->GetByteArrayRegion(reply,0,length,reinterpret_cast<jbyte*>(buffer.data()));
        if(env->ExceptionCheck()) return -1;
        return static_cast<jint>(reinterpret_cast<VulkanRenderer*>(session)->supplyTextTierGlyphs(buffer.data(),buffer.size()));
    } catch(const std::exception& error) { textFailure(env,error); return -1; }
}
JNIEXPORT jlong JNICALL Java_com_fluxx_android_engine_RenderBridge_upsertStyledText(JNIEnv* env,jobject,jlong session,jstring font,jbyteArray bytes,jboolean ligatures) {
    try {
        if(!bytes || env->GetArrayLength(bytes)>1024*1024) throw std::invalid_argument("Invalid styled text bytes");
        std::vector<uint8_t> buffer(static_cast<size_t>(env->GetArrayLength(bytes)));
        env->GetByteArrayRegion(bytes,0,static_cast<jsize>(buffer.size()),reinterpret_cast<jbyte*>(buffer.data()));
        if(env->ExceptionCheck()) return 0;
        const auto input=fluxx::text::decodeStyledInput(buffer.data(),buffer.size());
        return reinterpret_cast<VulkanRenderer*>(session)->upsertStyledText(textUtf8(env,font),input,ligatures);
    } catch(const std::exception& error) { textFailure(env,error);return 0; }
}
JNIEXPORT jlong JNICALL Java_com_fluxx_android_engine_RenderBridge_upsertPreparedText(JNIEnv* env,jobject,jlong session,jbyteArray bytes) {
    try {
        if(!bytes || static_cast<size_t>(env->GetArrayLength(bytes))>fluxx::text::MaxPreparedPacketBytes) throw std::invalid_argument("Invalid prepared text bytes");
        std::vector<uint8_t> buffer(static_cast<size_t>(env->GetArrayLength(bytes)));
        env->GetByteArrayRegion(bytes,0,static_cast<jsize>(buffer.size()),reinterpret_cast<jbyte*>(buffer.data()));
        if(env->ExceptionCheck()) return 0;
        return reinterpret_cast<VulkanRenderer*>(session)->upsertPreparedText(buffer.data(),buffer.size());
    } catch(const std::exception& error) { textFailure(env,error);return 0; }
}
JNIEXPORT void JNICALL Java_com_fluxx_android_engine_RenderBridge_clearTextCharacterBindings(JNIEnv*,jobject,jlong session) {
    reinterpret_cast<VulkanRenderer*>(session)->clearTextCharacterBindings();
}
JNIEXPORT void JNICALL Java_com_fluxx_android_engine_RenderBridge_bindTextCharacters(JNIEnv* env,jobject,jlong session,jlong handle,
    jlongArray catalogues,jintArray codepoints,jintArray fonts,jstring source,jobject modifiers,jobject codes) {
    try {
        if(!catalogues || !codepoints || !fonts || !source || !modifiers || !codes) throw std::invalid_argument("Missing character binding inputs");
        const auto count=env->GetArrayLength(catalogues),points=env->GetArrayLength(codepoints),characters=env->GetArrayLength(fonts);
        if(count<1 || count>64 || points<1 || points>4096 || characters>4096 || count*points>16384 ||
            env->GetDirectBufferCapacity(modifiers)<0 || env->GetDirectBufferCapacity(codes)<characters*4L) throw std::invalid_argument("Invalid character binding capacity");
        auto* m=static_cast<const fluxx::text::GlyphModifier*>(env->GetDirectBufferAddress(modifiers));
        auto* c=static_cast<const int32_t*>(env->GetDirectBufferAddress(codes));
        if(!m || !c || reinterpret_cast<uintptr_t>(m)%alignof(fluxx::text::GlyphModifier) || reinterpret_cast<uintptr_t>(c)%alignof(int32_t))
            throw std::invalid_argument("Character buffers must be direct and aligned");
        std::vector<jlong> h(count);std::vector<jint> cp(points),f(characters);
        env->GetLongArrayRegion(catalogues,0,count,h.data());env->GetIntArrayRegion(codepoints,0,points,cp.data());env->GetIntArrayRegion(fonts,0,characters,f.data());
        if(env->ExceptionCheck()) return;
        auto pin=std::make_shared<CharacterBufferPin>(env,modifiers,codes);if(env->ExceptionCheck()) return;
        reinterpret_cast<VulkanRenderer*>(session)->bindTextCharacters(handle,std::vector<int64_t>(h.begin(),h.end()),
            std::vector<uint32_t>(cp.begin(),cp.end()),std::vector<uint32_t>(f.begin(),f.end()),textUtf8(env,source),m,c,std::move(pin),
            static_cast<size_t>(env->GetDirectBufferCapacity(modifiers)));
    } catch(const std::exception& error) { textFailure(env,error); }
}
JNIEXPORT jboolean JNICALL Java_com_fluxx_android_engine_RenderBridge_reusePreparedText(JNIEnv* env,jobject,jlong session) {
    try { return reinterpret_cast<VulkanRenderer*>(session)->reusePreparedText(); }
    catch(const std::exception& error) { textFailure(env,error); return false; }
}
JNIEXPORT jboolean JNICALL Java_com_fluxx_android_engine_RenderBridge_textLayer(JNIEnv* env,jobject,jlong session,jlong handle,jfloatArray matrix,
    jfloat size,jint alignment,jint argb,jfloat opacity,jfloat tracking,jfloat leading,jfloat baselineShift,jint stroke,jfloat strokeWidth,jint paintOrder,jfloat softness,jobject modifiers) {
    try {
        if(!matrix || env->GetArrayLength(matrix)!=16) return false;
        float m[16]; env->GetFloatArrayRegion(matrix,0,16,m); if(env->ExceptionCheck()) return false;
        auto* data=modifiers?static_cast<const fluxx::text::GlyphModifier*>(env->GetDirectBufferAddress(modifiers)):nullptr;
        const jlong capacity=modifiers?env->GetDirectBufferCapacity(modifiers):0;
        if(modifiers && (capacity<0 || (!data && capacity))) throw std::invalid_argument("Text modifiers require direct storage");
        return reinterpret_cast<VulkanRenderer*>(session)->setTextLayer(handle,m,size,alignment,static_cast<uint32_t>(argb),opacity,tracking,leading,baselineShift,static_cast<uint32_t>(stroke),strokeWidth,paintOrder,softness,data,static_cast<size_t>(capacity));
    } catch(const std::exception& error) { textFailure(env,error); return false; }
}
JNIEXPORT jstring JNICALL Java_com_fluxx_android_engine_RenderBridge_textStats(JNIEnv* env,jobject,jlong session) {
    const auto stats=reinterpret_cast<VulkanRenderer*>(session)->textStatsJson(); return env->NewStringUTF(stats.c_str());
}
#ifdef FLUXX_TEXT_DIAGNOSTICS
JNIEXPORT jlong JNICALL Java_com_fluxx_android_engine_TextDebugBridge_allocationCount(JNIEnv*,jobject) {
    return fluxx::text::debug::allocationCount();
}
JNIEXPORT jlong JNICALL Java_com_fluxx_android_engine_TextDebugBridge_allocationCanary(JNIEnv*,jobject) {
    return fluxx::text::debug::allocationCanary();
}
JNIEXPORT void JNICALL Java_com_fluxx_android_engine_TextDebugBridge_frameCounters(JNIEnv* env,jobject,jlong session,jlong handle,jlongArray output) {
    try {
        if(env->GetArrayLength(output)!=6) throw std::invalid_argument("Expected six frame counters");
        int64_t values[6]; reinterpret_cast<VulkanRenderer*>(session)->textFrameCounters(handle,values);
        env->SetLongArrayRegion(output,0,6,values);
    } catch(const std::exception& error) { textFailure(env,error); }
}
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
