#include <jni.h>
#include <android/log.h>
#include <android/hardware_buffer_jni.h>
#include <android/asset_manager_jni.h>
#include <android/native_window_jni.h>
#include "engine_core.h"

extern "C" {

JNIEXPORT jint JNICALL
Java_com_fluxx_android_engine_FluxxEngine_nativePing(JNIEnv *env, jobject thiz, jint value) {
    __android_log_print(ANDROID_LOG_INFO, "FluxxEngine", "nativePing received: %d", value);
    return value + 1;
}

JNIEXPORT void JNICALL
Java_com_fluxx_android_engine_FluxxEngine_nativeInit(JNIEnv *env, jobject thiz, jobject surface, jint width, jint height, jobject assetManager) {
    if (!surface) return;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    EngineCore::getInstance()->init(window, width, height, mgr);
}

JNIEXPORT void JNICALL
Java_com_fluxx_android_engine_FluxxEngine_nativeStageHardwareBuffer(JNIEnv *env, jobject thiz, jobject hardwareBuffer, jlong generationId, jint cropWidth, jint cropHeight) {
    if (!hardwareBuffer) return;
    AHardwareBuffer* buffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    EngineCore::getInstance()->stageHardwareBuffer(buffer, (int64_t)generationId, (int)cropWidth, (int)cropHeight);
}

JNIEXPORT jlong JNICALL
Java_com_fluxx_android_engine_FluxxEngine_nativeGetLastConsumedGeneration(JNIEnv *env, jobject thiz) {
    return (jlong)EngineCore::getInstance()->getLastConsumedGeneration();
}

}
extern "C" JNIEXPORT void JNICALL
Java_com_fluxx_android_engine_FluxxEngine_nativeRenderFrame(JNIEnv* env, jobject thiz) {
    EngineCore::getInstance()->renderFrame(0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_fluxx_android_engine_FluxxEngine_nativeSetLayerTransform(JNIEnv* env, jobject thiz, jfloatArray matrix, jfloat opacity) {
    jfloat* mat = env->GetFloatArrayElements(matrix, nullptr);
    float m[16];
    for (int i = 0; i < 16; i++) m[i] = mat[i];
    env->ReleaseFloatArrayElements(matrix, mat, JNI_ABORT);
    EngineCore::getInstance()->setLayerTransform(m, opacity);
}

extern "C" JNIEXPORT void JNICALL
Java_com_fluxx_android_engine_FluxxEngine_nativeCleanup(JNIEnv* env, jobject thiz) {
    EngineCore::getInstance()->release();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fluxx_android_engine_FluxxEngine_nativeGetCompWidth(JNIEnv* env, jobject thiz) {
    return EngineCore::getInstance()->getCompWidth();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fluxx_android_engine_FluxxEngine_nativeGetCompHeight(JNIEnv* env, jobject thiz) {
    return EngineCore::getInstance()->getCompHeight();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fluxx_android_engine_FluxxEngine_nativeRenderExportFrame(JNIEnv* env, jobject thiz) {
    return EngineCore::getInstance()->renderExportFrame() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fluxx_android_engine_FluxxEngine_nativeReadbackPixels(JNIEnv* env, jobject thiz, jobject byteBuffer) {
    void* bufPtr = env->GetDirectBufferAddress(byteBuffer);
    jlong bufCapacity = env->GetDirectBufferCapacity(byteBuffer);
    if (!bufPtr || bufCapacity <= 0) return JNI_FALSE;
    return EngineCore::getInstance()->readbackOffscreenPixels(bufPtr, (uint32_t)bufCapacity) ? JNI_TRUE : JNI_FALSE;
}
