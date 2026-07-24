#pragma once

#include "vulkan_renderer.h"
#include "audio_processor.h"
#include <android/native_window.h>
#include <android/hardware_buffer.h>
#include <mutex>

class EngineCore {
public:
    static EngineCore* getInstance();

    bool init(ANativeWindow* window, int width, int height);
    void resize(int width, int height);
    void renderFrame(int64_t frameIndex);
    void release();

    void setPlayState(bool playing);
    bool bindHardwareBuffer(AHardwareBuffer* buffer);
    
    // Audio engine controls
    bool setupAudio();
    bool startAudio();
    void stopAudio();
    int64_t getAudioPositionMs();

private:
    EngineCore() = default;
    ~EngineCore() = default;

    static EngineCore* sInstance;
    static std::mutex sMutex;

    VulkanRenderer mRenderer;
    AudioProcessor mAudioProcessor;
    bool mInitialized = false;
};
