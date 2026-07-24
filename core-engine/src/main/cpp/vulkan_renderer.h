#pragma once

#include <vulkan/vulkan.h>
#include <android/native_window.h>
#include <android/hardware_buffer.h>
#include <vector>

class VulkanRenderer {
public:
    VulkanRenderer();
    ~VulkanRenderer();

    bool init(ANativeWindow* window);
    void resize(int width, int height);
    void render();
    void cleanup();

    bool bindHardwareBuffer(AHardwareBuffer* buffer);

private:
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
    int mWidth = 0;
    int mHeight = 0;

    VkInstance mInstance = VK_NULL_HANDLE;
    VkPhysicalDevice mPhysicalDevice = VK_NULL_HANDLE;
    VkDevice mDevice = VK_NULL_HANDLE;
    VkQueue mGraphicsQueue = VK_NULL_HANDLE;
    VkQueue mPresentQueue = VK_NULL_HANDLE;
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

    uint32_t mGraphicsQueueFamilyIndex = 0;
    uint32_t mPresentQueueFamilyIndex = 0;
    bool mInitialized = false;
};
