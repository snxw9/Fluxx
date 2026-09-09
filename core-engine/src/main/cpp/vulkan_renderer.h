#pragma once

#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>
#include <android/native_window.h>
#include <android/hardware_buffer.h>
#include <android/asset_manager.h>
#include <vector>
#include <atomic>


struct LayerTransform {
    float matrix[16];
    float opacity;
};

class VulkanRenderer {
public:
    void setLayerTransform(float matrix[16], float opacity);
public:
    VulkanRenderer();
    ~VulkanRenderer();

    bool init(ANativeWindow* window, AAssetManager* assetManager);
    void resize(int width, int height);
    void render();
    void cleanup();

    // Export pipeline
    bool renderExportFrame(); // Renders the scene into the offscreen FBO only (no swapchain blit, no present)
    VkImage getOffscreenImage() const { return mOffscreenImage; }
    VkFormat getOffscreenFormat() const { return VK_FORMAT_R8G8B8A8_UNORM; }
    bool readbackOffscreenPixels(void* outputBuffer, uint32_t bufferSize);

    int getCompWidth() const { return mCompWidth; }
    int getCompHeight() const { return mCompHeight; }

    void stageHardwareBuffer(AHardwareBuffer* buffer, int64_t generationId, int cropWidth, int cropHeight);
    int64_t getLastConsumedGeneration() const;

private:
    LayerTransform mLayerTransform;
    int mCompWidth = 1080;
    int mCompHeight = 1920;
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
    VkExtent2D mSwapchainExtent;
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

    // AHardwareBuffer tracking
    std::atomic<AHardwareBuffer*> mStagedBuffer{nullptr};
    std::atomic<int64_t> mStagedGeneration{-1};
    std::atomic<int> mStagedCropWidth{0};
    std::atomic<int> mStagedCropHeight{0};
    std::atomic<int64_t> mLastConsumedGeneration{-1};
    
    AHardwareBuffer* mCurrentRenderingBuffer = nullptr;
    int64_t mCurrentRenderingGeneration = -1;
    int mCurrentCropWidth = 0;
    int mCurrentCropHeight = 0;
    
    [[maybe_unused]] AHardwareBuffer* mPreviousFinishedBuffer = nullptr;
    [[maybe_unused]] int64_t mPreviousFinishedGeneration = -1;

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

    bool createVideoPipelineOnce(VkAndroidHardwareBufferFormatPropertiesANDROID& formatProps);
    bool createHardwareBufferImage(AHardwareBuffer* buffer);
    void cleanupVideoImage();
    void cleanupVideoPipeline();

};
