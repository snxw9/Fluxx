#include "engine_core.h"
#include "logger.h"

EngineCore* EngineCore::sInstance = nullptr;
std::mutex EngineCore::sMutex;

EngineCore* EngineCore::getInstance() {
    std::lock_guard<std::mutex> lock(sMutex);
    if (sInstance == nullptr) {
        sInstance = new EngineCore();
    }
    return sInstance;
}

bool EngineCore::init(ANativeWindow* window, int width, int height, AAssetManager* assetManager) {
    std::lock_guard<std::mutex> lock(sMutex);
    
    if (mInitialized) {
        LOGW("EngineCore is already initialized");
        return true;
    }

    LOGI("Initializing EngineCore with size %dx%d", width, height);

    if (!mRenderer.init(window, assetManager)) {
        LOGE("EngineCore: Vulkan renderer initialization failed");
        return false;
    }
    
    mRenderer.resize(width, height);
    mInitialized = true;
    LOGI("EngineCore: Initialized successfully");
    return true;
}

void EngineCore::resize(int width, int height) {
    if (!mInitialized) return;
    mRenderer.resize(width, height);
}

void EngineCore::renderFrame(int64_t frameIndex) {
    if (!mInitialized) return;
    
    // In actual engine, this will:
    // 1. Sync visual frame to audio timestamp if playing
    // 2. Build local/world matrices for scene DAG
    // 3. Process time -> masks -> effects -> transforms
    // 4. Render final composition to Vulkan swapchain
    
    mRenderer.render();
}

void EngineCore::release() {
    LOGI("EngineCore: Releasing engine resources");
    mAudioProcessor.cleanup();
    mRenderer.cleanup();
    mInitialized = false;
}

void EngineCore::setPlayState(bool playing) {
    LOGI("EngineCore: Play state changed to: %s", playing ? "PLAYING" : "STOPPED");
    if (playing) {
        startAudio();
    } else {
        stopAudio();
    }
}

void EngineCore::stageHardwareBuffer(AHardwareBuffer* buffer, int64_t generationId, int cropWidth, int cropHeight) {
    mRenderer.stageHardwareBuffer(buffer, generationId, cropWidth, cropHeight);
}

int64_t EngineCore::getLastConsumedGeneration() {
    return mRenderer.getLastConsumedGeneration();
}

bool EngineCore::setupAudio() {
    return mAudioProcessor.init();
}

bool EngineCore::startAudio() {
    return mAudioProcessor.start();
}

void EngineCore::stopAudio() {
    mAudioProcessor.stop();
}

int64_t EngineCore::getAudioPositionMs() {
    return mAudioProcessor.getCurrentAudioTimestamp();
}

void EngineCore::setLayerTransform(float matrix[16], float opacity) {
    mRenderer.setLayerTransform(matrix, opacity);
}

int EngineCore::getCompWidth() {
    return mRenderer.getCompWidth();
}

int EngineCore::getCompHeight() {
    return mRenderer.getCompHeight();
}

bool EngineCore::renderExportFrame() {
    return mRenderer.renderExportFrame();
}

bool EngineCore::readbackOffscreenPixels(void* outputBuffer, uint32_t bufferSize) {
    return mRenderer.readbackOffscreenPixels(outputBuffer, bufferSize);
}
