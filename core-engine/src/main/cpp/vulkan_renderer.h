#pragma once

#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>
#include <android/native_window.h>
#include <android/hardware_buffer.h>
#include <android/asset_manager.h>
#include <vector>
#include <atomic>
#include <mutex>
#include <map>
#include "text/text_renderer.h"


struct LayerTransform {
    float matrix[16];
    float opacity;
};

struct VideoLayerState {
    // YCbCr Video Pipeline state
    bool mVideoPipelineCreated = false;
    VkDescriptorSetLayout mVideoDescriptorSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout mVideoPipelineLayout = VK_NULL_HANDLE;
    VkPipeline mVideoPipeline = VK_NULL_HANDLE;
    VkDescriptorPool mVideoDescriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet mVideoDescriptorSet = VK_NULL_HANDLE;

    VkImage mVideoImage = VK_NULL_HANDLE;
    VkDeviceMemory mVideoMemory = VK_NULL_HANDLE;
    VkSamplerYcbcrConversion mYcbcrConversion = VK_NULL_HANDLE;
    VkSampler mVideoSampler = VK_NULL_HANDLE;
    VkImageView mVideoImageView = VK_NULL_HANDLE;

    AHardwareBuffer* buffer = nullptr;
    LayerTransform transform{};
    bool transitioned = false;
    uint64_t externalFormat = 0;
    VkFormat format = VK_FORMAT_UNDEFINED;
    uint64_t lastUse = 0;
};

class VulkanRenderer {
public:
    int64_t upsertText(const std::string& font, const std::string& utf8);
    bool prepareText(const std::vector<int64_t>& handles);
    bool setTextLayer(int64_t handle, const float* matrix, float size, int alignment, uint32_t argb, float opacity);
    void releaseText(int64_t handle);
    bool validationEnabled() const { return mValidationEnabled; }
    uint64_t validationErrors() const { return mValidationErrors.load(); }
    std::string textStatsJson() const;
    bool beginFrame(int width, int height, int presentationWidth = 0, int presentationHeight = 0);
    bool setFrameLayer(int64_t id, AHardwareBuffer* buffer, const float* matrix, float opacity);
    bool finishFrame();
    bool flushBatch();
    bool copyYuv(uint8_t* y, uint8_t* u, uint8_t* v, int yr, int ur, int vr, int up, int vp);
    // Render-thread only, after a completed paused frame. -1 denotes failure/outside canvas.
    int64_t readPreviewPixel(float normalizedX, float normalizedY);
public:
    VulkanRenderer();
    ~VulkanRenderer();

    bool init(ANativeWindow* window, AAssetManager* assetManager);
    void resize(int width, int height);
    bool render(int resizeRetries = 1);
    void cleanup();

    void releaseLayers();

    [[nodiscard]] int getCompWidth() const { return mCompWidth; }
    [[nodiscard]] int getCompHeight() const { return mCompHeight; }


private:
    bool readbackRgba();
    bool mReadbackValid = false;
    int mPreviewX = 0, mPreviewY = 0, mPreviewW = 0, mPreviewH = 0;
    int mPreviewBufferW = 0, mPreviewBufferH = 0;
    std::map<int64_t, VideoLayerState> mLayers;
    struct DrawEntry {
        int64_t rasterId=0;
        bool text=false;
        fluxx::text::TextMesh mesh{};
        fluxx::text::TextPush push{};
    };
    std::vector<DrawEntry> mDrawOrder;
    std::unique_ptr<fluxx::text::TextRenderer> mText;
    std::map<int64_t,std::shared_ptr<const fluxx::text::Layout>> mTextLayouts;
    int64_t mNextTextHandle=1;
    bool mTextPrepared=false;
    bool mValidationEnabled=false;
    std::atomic<uint64_t> mValidationErrors{0};
    VkDebugUtilsMessengerEXT mDebugMessenger=VK_NULL_HANDLE;
    static VKAPI_ATTR VkBool32 VKAPI_CALL debugMessage(VkDebugUtilsMessageSeverityFlagBitsEXT,
        VkDebugUtilsMessageTypeFlagsEXT, const VkDebugUtilsMessengerCallbackDataEXT*, void*);
    uint64_t mUseCounter = 0;
    VkPipelineCache mPipelineCache = VK_NULL_HANDLE;
    int mCompWidth = 1080;
    int mCompHeight = 1920;
    int mPresentationWidth = 1080;
    int mPresentationHeight = 1920;
    bool mFirstBatch = true;
    VkRenderPass mLoadRenderPass = VK_NULL_HANDLE;
    VkCommandBuffer mWorkCommand = VK_NULL_HANDLE;
    VkFence mWorkFence = VK_NULL_HANDLE;
    VkBuffer mReadback = VK_NULL_HANDLE;
    VkDeviceMemory mReadbackMemory = VK_NULL_HANDLE;
    void* mReadbackMapped = nullptr;
    VkDeviceSize mReadbackSize = 0;
    bool submitWork();
    bool beginWork();
    VkImage mOffscreenImage = VK_NULL_HANDLE;
    VkDeviceMemory mOffscreenMemory = VK_NULL_HANDLE;
    VkImageView mOffscreenImageView = VK_NULL_HANDLE;
    VkRenderPass mOffscreenRenderPass = VK_NULL_HANDLE;
    VkFramebuffer mOffscreenFramebuffer = VK_NULL_HANDLE;

    bool createOffscreenTarget();
    void cleanupOffscreenTarget();

    bool createInstance();
    bool selectPhysicalDevice();
    bool createDevice();
    bool createSwapchain();
    bool createRenderPass();
    bool createFramebuffers();
    bool createCommandPool();
    bool createCommandBuffers();
    bool createSyncObjects();

    void cleanupSwapchain();

    ANativeWindow* mWindow = nullptr;
    AAssetManager* mAssetManager = nullptr;
    int mWidth = 0;
    int mHeight = 0;

    VkInstance mInstance = VK_NULL_HANDLE;
    VkPhysicalDevice mPhysicalDevice = VK_NULL_HANDLE;
    VkDevice mDevice = VK_NULL_HANDLE;
    [[maybe_unused]] VkQueue mGraphicsQueue = VK_NULL_HANDLE;
    [[maybe_unused]] VkQueue mPresentQueue = VK_NULL_HANDLE;
    VkSurfaceKHR mSurface = VK_NULL_HANDLE;

    VkSwapchainKHR mSwapchain = VK_NULL_HANDLE;
    std::vector<VkImage> mSwapchainImages;
    VkFormat mSwapchainImageFormat;
    VkExtent2D mSwapchainExtent{};
    std::vector<VkImageView> mSwapchainImageViews;

    VkRenderPass mRenderPass = VK_NULL_HANDLE;
    std::vector<VkFramebuffer> mSwapchainFramebuffers;

    VkCommandPool mCommandPool = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> mCommandBuffers;

    VkSemaphore mImageAvailableSemaphore = VK_NULL_HANDLE;
    VkSemaphore mRenderFinishedSemaphore = VK_NULL_HANDLE;
    VkFence mInFlightFence = VK_NULL_HANDLE;

    [[maybe_unused]] uint32_t mGraphicsQueueFamilyIndex = 0;
    [[maybe_unused]] uint32_t mPresentQueueFamilyIndex = 0;
    bool mInitialized = false;

    bool createVideoPipelineOnce(VideoLayerState& layer, VkAndroidHardwareBufferFormatPropertiesANDROID& formatProps);
    bool createHardwareBufferImage(VideoLayerState& layer, AHardwareBuffer* buffer);
    void cleanupVideoImage(VideoLayerState& layer);
    void cleanupVideoPipeline(VideoLayerState& layer);

};
