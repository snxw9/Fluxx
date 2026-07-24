#include <jni.h>
#include <android/log.h>

extern "C" {

JNIEXPORT jint JNICALL
Java_com_fluxx_android_engine_FluxxEngine_nativePing(JNIEnv *env, jobject thiz, jint value) {
    __android_log_print(ANDROID_LOG_INFO, "FluxxEngine", "nativePing received: %d", value);
    return value + 1;
}

}
