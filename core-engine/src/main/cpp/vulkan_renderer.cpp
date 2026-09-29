#include "vulkan_renderer.h"
#include "logger.h"
#include <vulkan/vulkan_android.h>
#include <stdexcept>
#include <string>
#include <algorithm>

VulkanRenderer::VulkanRenderer() {
    LOGI("VulkanRenderer created");
}

VulkanRenderer::~VulkanRenderer() {
    cleanup();
    LOGI("VulkanRenderer destroyed");
}

bool VulkanRenderer::init(ANativeWindow* window, AAssetManager* assetManager) {
    if (mInitialized) {
        LOGW("VulkanRenderer already initialized, cleaning up first");
        cleanup();
    }

    mWindow = window;
    if (window) { mWidth = ANativeWindow_getWidth(window); mHeight = ANativeWindow_getHeight(window); }
    mAssetManager = assetManager;
    LOGI("Initializing Vulkan for window: %p", window);

    try {
        if (!createInstance()) { cleanup(); return false; }

    VkAndroidSurfaceCreateInfoKHR surfaceInfo{};
    surfaceInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    surfaceInfo.window = mWindow;
    if (window && vkCreateAndroidSurfaceKHR(mInstance, &surfaceInfo, nullptr, &mSurface) != VK_SUCCESS) {
        LOGE("Failed to create Android surface");
        { cleanup(); return false; }
    }

    if (!selectPhysicalDevice()) { cleanup(); return false; }
        if (!createDevice()) { cleanup(); return false; }
        VkPipelineCacheCreateInfo cacheInfo{VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO};
        if (vkCreatePipelineCache(mDevice, &cacheInfo, nullptr, &mPipelineCache) != VK_SUCCESS) { cleanup(); return false; }
        if (!createOffscreenTarget()) { cleanup(); return false; }
        if (window && !createSwapchain()) { cleanup(); return false; }
        if (!createCommandPool()) { cleanup(); return false; }
        if (window && !createCommandBuffers()) { cleanup(); return false; }
        if (!createSyncObjects()) { cleanup(); return false; }
        VkCommandBufferAllocateInfo alloc{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        alloc.commandPool = mCommandPool; alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; alloc.commandBufferCount = 1;
        if (vkAllocateCommandBuffers(mDevice, &alloc, &mWorkCommand) != VK_SUCCESS) { cleanup(); return false; }
        VkFenceCreateInfo fence{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fence.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        if (vkCreateFence(mDevice, &fence, nullptr, &mWorkFence) != VK_SUCCESS) { cleanup(); return false; }

        mInitialized = true;
        LOGI("VulkanRenderer initialized successfully");
        return true;
    } catch (const std::exception& e) {
        LOGE("Failed to initialize Vulkan: %s", e.what());
        cleanup();
        return false;
    }
}

void VulkanRenderer::resize(int width, int height) {
    if (!mInitialized || !mWindow || width <= 0 || height <= 0) return;
    LOGI("VulkanRenderer resize to: %dx%d", width, height);
    mWidth = width;
    mHeight = height;

    if (mDevice != VK_NULL_HANDLE && mDevice != (VkDevice)1) {
        vkDeviceWaitIdle(mDevice);
        cleanupSwapchain();
        if (!mCommandBuffers.empty()) {
            vkFreeCommandBuffers(mDevice, mCommandPool, (uint32_t)mCommandBuffers.size(), mCommandBuffers.data());
            mCommandBuffers.clear();
        }
        if (!createSwapchain() || !createCommandBuffers()) cleanupSwapchain();
        else LOGI("Preview surface requested %dx%d, swapchain extent %ux%u, composition %dx%d",
                  width, height, mSwapchainExtent.width, mSwapchainExtent.height, mCompWidth, mCompHeight);
    }
}

bool VulkanRenderer::render(int resizeRetries) {
    if (!mInitialized || mDevice == VK_NULL_HANDLE || mDevice == (VkDevice)1 || !mSwapchain || mWidth <= 0 || mHeight <= 0) return false;

    if (vkWaitForFences(mDevice, 1, &mInFlightFence, VK_TRUE, 1000000000ULL) != VK_SUCCESS) return false;

    uint32_t imageIndex;
    VkResult result = vkAcquireNextImageKHR(mDevice, mSwapchain, 1000000000ULL, mImageAvailableSemaphore, VK_NULL_HANDLE, &imageIndex);

    if (result == VK_ERROR_OUT_OF_DATE_KHR) {
        resize(mWidth, mHeight);
        // A paused preview has no next playback frame to repair a resize.
        return resizeRetries > 0 && render(resizeRetries - 1);
    } else if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
        LOGE("Failed to acquire swapchain image!");
        return false;
    }


    VkCommandBuffer commandBuffer = mCommandBuffers[imageIndex];
    vkResetCommandBuffer(commandBuffer, 0);

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;

    if (vkBeginCommandBuffer(commandBuffer, &beginInfo) != VK_SUCCESS) {
        LOGE("Failed to begin recording command buffer!");
        return false;
    }



    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = mSwapchainImages[imageIndex];
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.baseMipLevel = 0;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.baseArrayLayer = 0;
    barrier.subresourceRange.layerCount = 1;
    barrier.srcAccessMask = 0;
    barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;

    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);

    VkClearColorValue clearVal = {{0.0f, 0.0f, 0.0f, 1.0f}};
    VkImageSubresourceRange range{};
    range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    range.levelCount = 1;
    range.layerCount = 1;
    vkCmdClearColorImage(commandBuffer, mSwapchainImages[imageIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &clearVal, 1, &range);

    // Fit in displayed workspace pixels, then map into the acquired buffer.
    // During resize Android may display a buffer with a different extent; fitting
    // to that buffer's aspect instead would stretch the composition on screen.
    const double displayScale = std::min(static_cast<double>(mWidth) / mPresentationWidth,
                                         static_cast<double>(mHeight) / mPresentationHeight);
    const int dstW = std::max(1, std::min(static_cast<int>(mSwapchainExtent.width),
        static_cast<int>(mPresentationWidth * displayScale * mSwapchainExtent.width / mWidth + 0.5)));
    const int dstH = std::max(1, std::min(static_cast<int>(mSwapchainExtent.height),
        static_cast<int>(mPresentationHeight * displayScale * mSwapchainExtent.height / mHeight + 0.5)));
    const int dstX = (static_cast<int>(mSwapchainExtent.width) - dstW) / 2;
    const int dstY = (static_cast<int>(mSwapchainExtent.height) - dstH) / 2;
    // Single source for eyedropper coordinates: the exact integer blit rectangle.
    mPreviewX=dstX; mPreviewY=dstY; mPreviewW=dstW; mPreviewH=dstH;
    mPreviewBufferW=mSwapchainExtent.width; mPreviewBufferH=mSwapchainExtent.height;

    VkImageBlit blit{};
    blit.srcOffsets[0] = {0, 0, 0};
    blit.srcOffsets[1] = {mCompWidth, mCompHeight, 1};
    blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.srcSubresource.mipLevel = 0;
    blit.srcSubresource.baseArrayLayer = 0;
    blit.srcSubresource.layerCount = 1;

    blit.dstOffsets[0] = {dstX, dstY, 0};
    blit.dstOffsets[1] = {dstX + dstW, dstY + dstH, 1};
    blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.dstSubresource.mipLevel = 0;
    blit.dstSubresource.baseArrayLayer = 0;
    blit.dstSubresource.layerCount = 1;

    vkCmdBlitImage(commandBuffer, mOffscreenImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, mSwapchainImages[imageIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);

    barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    barrier.dstAccessMask = 0;
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);

    if (vkEndCommandBuffer(commandBuffer) != VK_SUCCESS) {
        LOGE("Failed to record command buffer!");
        return false;
    }

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;

    VkSemaphore waitSemaphores[] = {mImageAvailableSemaphore};
    VkPipelineStageFlags waitStages[] = {VK_PIPELINE_STAGE_TRANSFER_BIT};
    submitInfo.waitSemaphoreCount = 1;
    submitInfo.pWaitSemaphores = waitSemaphores;
    submitInfo.pWaitDstStageMask = waitStages;

    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer;

    VkSemaphore signalSemaphores[] = {mRenderFinishedSemaphore};
    submitInfo.signalSemaphoreCount = 1;
    submitInfo.pSignalSemaphores = signalSemaphores;

    vkResetFences(mDevice, 1, &mInFlightFence);
    if (vkQueueSubmit(mGraphicsQueue, 1, &submitInfo, mInFlightFence) != VK_SUCCESS) {
        LOGE("Failed to submit draw command buffer!");
        return false;
    }

    VkPresentInfoKHR presentInfo{};
    presentInfo.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    presentInfo.waitSemaphoreCount = 1;
    presentInfo.pWaitSemaphores = signalSemaphores;

    VkSwapchainKHR swapchains[] = {mSwapchain};
    presentInfo.swapchainCount = 1;
    presentInfo.pSwapchains = swapchains;
    presentInfo.pImageIndices = &imageIndex;

    result = vkQueuePresentKHR(mPresentQueue, &presentInfo);

    if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) {
        resize(mWidth, mHeight);
        if (result == VK_ERROR_OUT_OF_DATE_KHR) return resizeRetries > 0 && render(resizeRetries - 1);
    } else if (result != VK_SUCCESS) {
        LOGE("Failed to present swapchain image!");
        return false;
    }
    return true;
}

void VulkanRenderer::cleanup() {
    if (mDevice != VK_NULL_HANDLE) vkDeviceWaitIdle(mDevice);
    LOGI("Cleaning up Vulkan resources");

    releaseLayers();
    cleanupSwapchain();
    cleanupOffscreenTarget();

    if (mRenderFinishedSemaphore != VK_NULL_HANDLE) {
        vkDestroySemaphore(mDevice, mRenderFinishedSemaphore, nullptr);
        mRenderFinishedSemaphore = VK_NULL_HANDLE;
    }
    if (mImageAvailableSemaphore != VK_NULL_HANDLE) {
        vkDestroySemaphore(mDevice, mImageAvailableSemaphore, nullptr);
        mImageAvailableSemaphore = VK_NULL_HANDLE;
    }
    if (mInFlightFence != VK_NULL_HANDLE) {
        vkDestroyFence(mDevice, mInFlightFence, nullptr);
        mInFlightFence = VK_NULL_HANDLE;
    }
    if (mWorkFence) vkDestroyFence(mDevice,mWorkFence,nullptr);
    mWorkFence=VK_NULL_HANDLE; mWorkCommand=VK_NULL_HANDLE;
    if (mCommandPool != VK_NULL_HANDLE) {
        vkDestroyCommandPool(mDevice, mCommandPool, nullptr);
        mCommandPool = VK_NULL_HANDLE;
    }
    if (mDevice != VK_NULL_HANDLE) {
        if (mPipelineCache) vkDestroyPipelineCache(mDevice,mPipelineCache,nullptr);
        mPipelineCache=VK_NULL_HANDLE;
        vkDestroyDevice(mDevice, nullptr);
        mDevice = VK_NULL_HANDLE;
    }
    if (mInstance != VK_NULL_HANDLE) {
        if (mSurface != VK_NULL_HANDLE) {
            // Android extensions: vkDestroySurfaceKHR
            // Normally load via vkGetInstanceProcAddr or standard link
            vkDestroySurfaceKHR(mInstance, mSurface, nullptr);
            mSurface = VK_NULL_HANDLE;
        }
        vkDestroyInstance(mInstance, nullptr);
        mInstance = VK_NULL_HANDLE;
    }

    if (mWindow) ANativeWindow_release(mWindow);
    mWindow = nullptr;
    mInitialized = false;
    LOGI("Vulkan cleanup complete");
}

void VulkanRenderer::cleanupSwapchain() {
    for (auto framebuffer : mSwapchainFramebuffers) {
        vkDestroyFramebuffer(mDevice, framebuffer, nullptr);
    }
    mSwapchainFramebuffers.clear();

    if (mRenderPass != VK_NULL_HANDLE) {
        vkDestroyRenderPass(mDevice, mRenderPass, nullptr);
        mRenderPass = VK_NULL_HANDLE;
    }

    for (auto imageView : mSwapchainImageViews) {
        vkDestroyImageView(mDevice, imageView, nullptr);
    }
    mSwapchainImageViews.clear();

    if (mSwapchain != VK_NULL_HANDLE) {
        vkDestroySwapchainKHR(mDevice, mSwapchain, nullptr);
        mSwapchain = VK_NULL_HANDLE;
    }
}

// Private helper methods - Stubs logging the flow

bool VulkanRenderer::createInstance() {
    LOGI("Vulkan: Creating VkInstance...");
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "Fluxx";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "FluxxEngine";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1; // Guarantees Android 10+ baseline

    std::vector<const char*> extensions = {
        "VK_KHR_surface",
        "VK_KHR_android_surface",
        "VK_KHR_get_physical_device_properties2"
    };

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;
    createInfo.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    createInfo.ppEnabledExtensionNames = extensions.data();

    // In a real NDK load, vkCreateInstance compiles directly or gets loaded via Vulkan loader
    // We stub this or call it. To ensure it compiles and runs, we use standard VkInstance creation.
    // Under NDK, vkCreateInstance is linked dynamically via libvulkan.so.
    VkResult result = vkCreateInstance(&createInfo, nullptr, &mInstance);
    if (result != VK_SUCCESS) {
        LOGE("vkCreateInstance failed with code: %d", result);
        // On emulator/test devices without Vulkan, log it. For Phase 0, we can fall back to mock init if hardware Vulkan init fails.
        mInstance = VK_NULL_HANDLE;
        return false;
    } else {
        LOGI("VkInstance created successfully at %p", mInstance);
    }
    return true;
}

bool VulkanRenderer::selectPhysicalDevice() {
    LOGI("Vulkan: Selecting VkPhysicalDevice...");
    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(mInstance, &deviceCount, nullptr);
    if (deviceCount == 0) {
        LOGW("No physical devices with Vulkan support found. Using mock physical device.");
        return false;
    }

    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(mInstance, &deviceCount, devices.data());
    mPhysicalDevice = devices[0];
    LOGI("Vulkan: Selected physical device: %p", mPhysicalDevice);
    return true;
}

bool VulkanRenderer::createDevice() {
    LOGI("Vulkan: Creating VkDevice...");
    uint32_t familyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(mPhysicalDevice, &familyCount, nullptr);
    std::vector<VkQueueFamilyProperties> families(familyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(mPhysicalDevice, &familyCount, families.data());
    bool found = false;
    for (uint32_t i = 0; i < familyCount; ++i) {
        VkBool32 present = VK_FALSE;
        if ((mSurface == VK_NULL_HANDLE || (vkGetPhysicalDeviceSurfaceSupportKHR(mPhysicalDevice, i, mSurface, &present) == VK_SUCCESS && present)) && (families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)) {
            mGraphicsQueueFamilyIndex = mPresentQueueFamilyIndex = i;
            found = true;
            break;
        }
    }
    if (!found) return false;
    VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcr{};
    ycbcr.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES;
    VkPhysicalDeviceFeatures2 features{};
    features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    features.pNext = &ycbcr;
    vkGetPhysicalDeviceFeatures2(mPhysicalDevice, &features);
    if (!ycbcr.samplerYcbcrConversion) return false;
    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueCreateInfo{};
    queueCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueCreateInfo.queueFamilyIndex = mGraphicsQueueFamilyIndex;
    queueCreateInfo.queueCount = 1;
    queueCreateInfo.pQueuePriorities = &queuePriority;

    std::vector<const char*> deviceExtensions = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME,
        "VK_ANDROID_external_memory_android_hardware_buffer",
        "VK_KHR_sampler_ycbcr_conversion",
        "VK_KHR_external_memory",
        "VK_KHR_bind_memory2"
    };

    VkDeviceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    createInfo.pNext = &ycbcr;
    createInfo.queueCreateInfoCount = 1;
    createInfo.pQueueCreateInfos = &queueCreateInfo;
    createInfo.enabledExtensionCount = static_cast<uint32_t>(deviceExtensions.size());
    createInfo.ppEnabledExtensionNames = deviceExtensions.data();

    VkResult result = vkCreateDevice(mPhysicalDevice, &createInfo, nullptr, &mDevice);
    if (result != VK_SUCCESS) {
        LOGE("vkCreateDevice failed with code: %d", result);
        mDevice = VK_NULL_HANDLE;
        return false;
    } else {
        LOGI("VkDevice created successfully at %p", mDevice);
        vkGetDeviceQueue(mDevice, mGraphicsQueueFamilyIndex, 0, &mGraphicsQueue);
        mPresentQueue = mGraphicsQueue;
    }
    return true;
}

bool VulkanRenderer::createSwapchain() {
    VkSurfaceCapabilitiesKHR capabilities{};
    if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(mPhysicalDevice, mSurface, &capabilities) != VK_SUCCESS) return false;

    uint32_t formatCount = 0;
    if (vkGetPhysicalDeviceSurfaceFormatsKHR(mPhysicalDevice, mSurface, &formatCount, nullptr) != VK_SUCCESS || formatCount == 0) return false;
    std::vector<VkSurfaceFormatKHR> formats(formatCount);
    vkGetPhysicalDeviceSurfaceFormatsKHR(mPhysicalDevice, mSurface, &formatCount, formats.data());

    VkSurfaceFormatKHR surfaceFormat = formats[0];
    for (const auto& availableFormat : formats) {
        if (availableFormat.format == VK_FORMAT_R8G8B8A8_UNORM || availableFormat.format == VK_FORMAT_B8G8R8A8_UNORM) {
            surfaceFormat = availableFormat;
            break;
        }
    }

    uint32_t imageCount = capabilities.minImageCount + 1;
    if (capabilities.maxImageCount > 0 && imageCount > capabilities.maxImageCount) {
        imageCount = capabilities.maxImageCount;
    }

    VkSwapchainCreateInfoKHR createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    createInfo.surface = mSurface;
    createInfo.minImageCount = imageCount;
    createInfo.imageFormat = surfaceFormat.format;
    createInfo.imageColorSpace = surfaceFormat.colorSpace;

    if (capabilities.currentExtent.width != 0xFFFFFFFF) {
        createInfo.imageExtent = capabilities.currentExtent;
    } else {
        createInfo.imageExtent = { (uint32_t)mWidth, (uint32_t)mHeight };
    }

    createInfo.imageArrayLayers = 1;
    createInfo.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    uint32_t queueFamilyIndices[] = {0}; // Assuming graphics family is 0
    createInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    createInfo.queueFamilyIndexCount = 1;
    createInfo.pQueueFamilyIndices = queueFamilyIndices;

    createInfo.preTransform = capabilities.currentTransform;
    createInfo.compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    createInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    createInfo.clipped = VK_TRUE;
    createInfo.oldSwapchain = VK_NULL_HANDLE;

    if (vkCreateSwapchainKHR(mDevice, &createInfo, nullptr, &mSwapchain) != VK_SUCCESS) {
        LOGE("Failed to create swapchain!");
        return false;
    }

    mSwapchainImageFormat = surfaceFormat.format;
    mSwapchainExtent = createInfo.imageExtent;

    vkGetSwapchainImagesKHR(mDevice, mSwapchain, &imageCount, nullptr);
    mSwapchainImages.resize(imageCount);
    vkGetSwapchainImagesKHR(mDevice, mSwapchain, &imageCount, mSwapchainImages.data());

    mSwapchainImageViews.resize(mSwapchainImages.size());
    for (size_t i = 0; i < mSwapchainImages.size(); i++) {
        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = mSwapchainImages[i];
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = mSwapchainImageFormat;
        viewInfo.components.r = VK_COMPONENT_SWIZZLE_IDENTITY;
        viewInfo.components.g = VK_COMPONENT_SWIZZLE_IDENTITY;
        viewInfo.components.b = VK_COMPONENT_SWIZZLE_IDENTITY;
        viewInfo.components.a = VK_COMPONENT_SWIZZLE_IDENTITY;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.baseMipLevel = 0;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.baseArrayLayer = 0;
        viewInfo.subresourceRange.layerCount = 1;

        if (vkCreateImageView(mDevice, &viewInfo, nullptr, &mSwapchainImageViews[i]) != VK_SUCCESS) {
            LOGE("Failed to create image views!");
            return false;
        }
    }

    return true;
}

bool VulkanRenderer::createRenderPass() {
    VkAttachmentDescription colorAttachment{};
    colorAttachment.format = mSwapchainImageFormat;
    colorAttachment.samples = VK_SAMPLE_COUNT_1_BIT;
    colorAttachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    colorAttachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    colorAttachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    colorAttachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    colorAttachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    colorAttachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

    VkAttachmentReference colorAttachmentRef{};
    colorAttachmentRef.attachment = 0;
    colorAttachmentRef.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &colorAttachmentRef;

    VkSubpassDependency dependency{};
    dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
    dependency.dstSubpass = 0;
    dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependency.srcAccessMask = 0;
    dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

    VkRenderPassCreateInfo renderPassInfo{};
    renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    renderPassInfo.attachmentCount = 1;
    renderPassInfo.pAttachments = &colorAttachment;
    renderPassInfo.subpassCount = 1;
    renderPassInfo.pSubpasses = &subpass;


    renderPassInfo.dependencyCount = 1;
    renderPassInfo.pDependencies = &dependency;

    if (vkCreateRenderPass(mDevice, &renderPassInfo, nullptr, &mRenderPass) != VK_SUCCESS) {
        LOGE("Failed to create render pass!");
        return false;
    }
    return true;
}

bool VulkanRenderer::createFramebuffers() {
    mSwapchainFramebuffers.resize(mSwapchainImageViews.size());

    for (size_t i = 0; i < mSwapchainImageViews.size(); i++) {
        VkImageView attachments[] = {
            mSwapchainImageViews[i]
        };

        VkFramebufferCreateInfo framebufferInfo{};
        framebufferInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        framebufferInfo.renderPass = mRenderPass;
        framebufferInfo.attachmentCount = 1;
        framebufferInfo.pAttachments = attachments;
        framebufferInfo.width = mSwapchainExtent.width;
        framebufferInfo.height = mSwapchainExtent.height;
        framebufferInfo.layers = 1;

        if (vkCreateFramebuffer(mDevice, &framebufferInfo, nullptr, &mSwapchainFramebuffers[i]) != VK_SUCCESS) {
            LOGE("Failed to create framebuffer!");
            return false;
        }
    }
    return true;
}

bool VulkanRenderer::createCommandPool() {
    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = mGraphicsQueueFamilyIndex;

    if (vkCreateCommandPool(mDevice, &poolInfo, nullptr, &mCommandPool) != VK_SUCCESS) {
        LOGE("Failed to create command pool!");
        return false;
    }
    return true;
}

bool VulkanRenderer::createCommandBuffers() {
    mCommandBuffers.resize(mSwapchainImages.size());

    VkCommandBufferAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocInfo.commandPool = mCommandPool;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = (uint32_t) mCommandBuffers.size();

    if (vkAllocateCommandBuffers(mDevice, &allocInfo, mCommandBuffers.data()) != VK_SUCCESS) {
        LOGE("Failed to allocate command buffers!");
        return false;
    }
    return true;
}

bool VulkanRenderer::createSyncObjects() {
    VkSemaphoreCreateInfo semaphoreInfo{};
    semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;

    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;

    if (vkCreateSemaphore(mDevice, &semaphoreInfo, nullptr, &mImageAvailableSemaphore) != VK_SUCCESS ||
        vkCreateSemaphore(mDevice, &semaphoreInfo, nullptr, &mRenderFinishedSemaphore) != VK_SUCCESS ||
        vkCreateFence(mDevice, &fenceInfo, nullptr, &mInFlightFence) != VK_SUCCESS) {
        LOGE("Failed to create synchronization objects!");
        return false;
    }
    return true;
}

void VulkanRenderer::cleanupVideoImage(VideoLayerState& layer) {
    if (mDevice == VK_NULL_HANDLE || mDevice == (VkDevice)1) return;

    if (layer.mVideoImageView != VK_NULL_HANDLE) {
        vkDestroyImageView(mDevice, layer.mVideoImageView, nullptr);
        layer.mVideoImageView = VK_NULL_HANDLE;
    }
    if (layer.mVideoImage != VK_NULL_HANDLE) {
        vkDestroyImage(mDevice, layer.mVideoImage, nullptr);
        layer.mVideoImage = VK_NULL_HANDLE;
    }
    if (layer.mVideoMemory != VK_NULL_HANDLE) {
        vkFreeMemory(mDevice, layer.mVideoMemory, nullptr);
        layer.mVideoMemory = VK_NULL_HANDLE;
    }
}

bool VulkanRenderer::createHardwareBufferImage(VideoLayerState& layer, AHardwareBuffer* buffer) {
    if (mDevice == VK_NULL_HANDLE || mDevice == (VkDevice)1) return false;

    auto vkGetAndroidHardwareBufferPropertiesANDROID =
        (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)vkGetDeviceProcAddr(
            mDevice, "vkGetAndroidHardwareBufferPropertiesANDROID");

    if (!vkGetAndroidHardwareBufferPropertiesANDROID) return false;

    VkAndroidHardwareBufferFormatPropertiesANDROID formatProps{};
    formatProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;

    VkAndroidHardwareBufferPropertiesANDROID props{};
    props.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    props.pNext = &formatProps;

    if (vkGetAndroidHardwareBufferPropertiesANDROID(mDevice, buffer, &props) != VK_SUCCESS) return false;

    if (layer.format != formatProps.format || layer.externalFormat != formatProps.externalFormat) cleanupVideoPipeline(layer);
    layer.format=formatProps.format; layer.externalFormat=formatProps.externalFormat;
    if (!createVideoPipelineOnce(layer, formatProps)) { cleanupVideoPipeline(layer); return false; }

    // Create Image
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(buffer, &desc);

    VkExternalFormatANDROID externalFormat{};
    externalFormat.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
    externalFormat.externalFormat = formatProps.externalFormat;

    VkExternalMemoryImageCreateInfo extMemImageInfo{};
    extMemImageInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    extMemImageInfo.pNext = formatProps.format == VK_FORMAT_UNDEFINED ? &externalFormat : nullptr;
    extMemImageInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.pNext = &extMemImageInfo;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = formatProps.format;
    imageInfo.extent = {desc.width, desc.height, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if (vkCreateImage(mDevice, &imageInfo, nullptr, &layer.mVideoImage) != VK_SUCCESS) return false;

    // Import Memory
    VkImportAndroidHardwareBufferInfoANDROID importInfo{};
    importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    importInfo.buffer = buffer;
    VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
    dedicated.image=layer.mVideoImage;
    importInfo.pNext=&dedicated;

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.pNext = &importInfo;
    allocInfo.allocationSize = props.allocationSize;

    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(mPhysicalDevice, &memProps);
    allocInfo.memoryTypeIndex = 0;
    bool foundMemoryType = false;
    for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
        if ((props.memoryTypeBits & (1 << i))) {
            allocInfo.memoryTypeIndex = i;
            foundMemoryType = true;
            break;
        }
    }

    if (!foundMemoryType) return false;

    if (vkAllocateMemory(mDevice, &allocInfo, nullptr, &layer.mVideoMemory) != VK_SUCCESS) return false;
    if (vkBindImageMemory(mDevice, layer.mVideoImage, layer.mVideoMemory, 0) != VK_SUCCESS) return false;

    // Create Image View
    VkSamplerYcbcrConversionInfo ycbcrConversionInfo{};
    ycbcrConversionInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO;
    ycbcrConversionInfo.conversion = layer.mYcbcrConversion;

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.pNext = layer.mYcbcrConversion != VK_NULL_HANDLE ? &ycbcrConversionInfo : nullptr;
    viewInfo.image = layer.mVideoImage;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = formatProps.format;
    viewInfo.components.r = VK_COMPONENT_SWIZZLE_IDENTITY;
    viewInfo.components.g = VK_COMPONENT_SWIZZLE_IDENTITY;
    viewInfo.components.b = VK_COMPONENT_SWIZZLE_IDENTITY;
    viewInfo.components.a = VK_COMPONENT_SWIZZLE_IDENTITY;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.baseMipLevel = 0;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.baseArrayLayer = 0;
    viewInfo.subresourceRange.layerCount = 1;

    if (vkCreateImageView(mDevice, &viewInfo, nullptr, &layer.mVideoImageView) != VK_SUCCESS) return false;

    // Update Descriptor Set
    VkDescriptorImageInfo descImageInfo{};
    descImageInfo.sampler = layer.mVideoSampler;
    descImageInfo.imageView = layer.mVideoImageView;
    descImageInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    VkWriteDescriptorSet writeSet{};
    writeSet.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writeSet.dstSet = layer.mVideoDescriptorSet;
    writeSet.dstBinding = 0;
    writeSet.dstArrayElement = 0;
    writeSet.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writeSet.descriptorCount = 1;
    writeSet.pImageInfo = &descImageInfo;

    vkUpdateDescriptorSets(mDevice, 1, &writeSet, 0, nullptr);

    return true;
}
static std::vector<char> readAsset(AAssetManager* mgr, const char* filename) {
    if (!mgr) return {};
    AAsset* asset = AAssetManager_open(mgr, filename, AASSET_MODE_BUFFER);
    if (!asset) return {};
    size_t length = AAsset_getLength(asset);
    std::vector<char> buffer(length);
    AAsset_read(asset, buffer.data(), length);
    AAsset_close(asset);
    return buffer;
}

static VkShaderModule createShaderModule(VkDevice device, const std::vector<char>& code) {
    if (code.empty()) return VK_NULL_HANDLE;
    VkShaderModuleCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    createInfo.codeSize = code.size();
    createInfo.pCode = reinterpret_cast<const uint32_t*>(code.data());
    VkShaderModule shaderModule;
    if (vkCreateShaderModule(device, &createInfo, nullptr, &shaderModule) != VK_SUCCESS) {
        return VK_NULL_HANDLE;
    }
    return shaderModule;
}

void VulkanRenderer::cleanupVideoPipeline(VideoLayerState& layer) {
    if (mDevice == VK_NULL_HANDLE || mDevice == (VkDevice)1) return;
    if (layer.mVideoPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(mDevice, layer.mVideoPipeline, nullptr);
        layer.mVideoPipeline = VK_NULL_HANDLE;
    }
    if (layer.mVideoPipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(mDevice, layer.mVideoPipelineLayout, nullptr);
        layer.mVideoPipelineLayout = VK_NULL_HANDLE;
    }
    if (layer.mVideoDescriptorSetLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(mDevice, layer.mVideoDescriptorSetLayout, nullptr);
        layer.mVideoDescriptorSetLayout = VK_NULL_HANDLE;
    }
    if (layer.mVideoDescriptorPool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(mDevice, layer.mVideoDescriptorPool, nullptr);
        layer.mVideoDescriptorPool = VK_NULL_HANDLE;
    }
    if (layer.mVideoSampler != VK_NULL_HANDLE) {
        vkDestroySampler(mDevice, layer.mVideoSampler, nullptr);
        layer.mVideoSampler = VK_NULL_HANDLE;
    }
    if (layer.mYcbcrConversion != VK_NULL_HANDLE) {
        vkDestroySamplerYcbcrConversion(mDevice, layer.mYcbcrConversion, nullptr);
        layer.mYcbcrConversion = VK_NULL_HANDLE;
    }
    layer.mVideoPipelineCreated = false;
}

bool VulkanRenderer::createVideoPipelineOnce(VideoLayerState& layer, VkAndroidHardwareBufferFormatPropertiesANDROID& formatProps) {
    if (layer.mVideoPipelineCreated) return true;

    if (layer.mYcbcrConversion != VK_NULL_HANDLE) {
        vkDestroySamplerYcbcrConversion(mDevice, layer.mYcbcrConversion, nullptr);
        layer.mYcbcrConversion = VK_NULL_HANDLE;
    }

    // 1. Create YCbCr Conversion
    VkExternalFormatANDROID externalFormat{};
    externalFormat.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
    externalFormat.externalFormat = formatProps.externalFormat;

    VkSamplerYcbcrConversionCreateInfo ycbcrCreateInfo{};
    ycbcrCreateInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO;
    ycbcrCreateInfo.pNext = formatProps.format == VK_FORMAT_UNDEFINED ? &externalFormat : nullptr;
    ycbcrCreateInfo.format = formatProps.format;
    ycbcrCreateInfo.ycbcrModel = formatProps.suggestedYcbcrModel;
    ycbcrCreateInfo.ycbcrRange = formatProps.suggestedYcbcrRange;
    ycbcrCreateInfo.components = formatProps.samplerYcbcrConversionComponents;
    ycbcrCreateInfo.xChromaOffset = formatProps.suggestedXChromaOffset;
    ycbcrCreateInfo.yChromaOffset = formatProps.suggestedYChromaOffset;
    ycbcrCreateInfo.chromaFilter = VK_FILTER_LINEAR;
    ycbcrCreateInfo.forceExplicitReconstruction = VK_FALSE;

    const bool rgba = formatProps.format == VK_FORMAT_R8G8B8A8_UNORM || formatProps.format == VK_FORMAT_R8G8B8A8_SRGB;
    if (!rgba && vkCreateSamplerYcbcrConversion(mDevice, &ycbcrCreateInfo, nullptr, &layer.mYcbcrConversion) != VK_SUCCESS) {
        LOGE("Failed to create YCbCr conversion");
        return false;
    }

    // 2. Create Immutable Sampler
    VkSamplerYcbcrConversionInfo ycbcrConversionInfo{};
    ycbcrConversionInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO;
    ycbcrConversionInfo.conversion = layer.mYcbcrConversion;

    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.pNext = layer.mYcbcrConversion != VK_NULL_HANDLE ? &ycbcrConversionInfo : nullptr;
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.mipLodBias = 0.0f;
    samplerInfo.compareOp = VK_COMPARE_OP_NEVER;
    samplerInfo.minLod = 0.0f;
    samplerInfo.maxLod = 1.0f;
    samplerInfo.borderColor = VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE;
    samplerInfo.maxAnisotropy = 1.0f;
    samplerInfo.anisotropyEnable = VK_FALSE;
    samplerInfo.unnormalizedCoordinates = VK_FALSE;

    if (vkCreateSampler(mDevice, &samplerInfo, nullptr, &layer.mVideoSampler) != VK_SUCCESS) {
        LOGE("Failed to create video sampler");
        return false;
    }

    // 3. Create Descriptor Set Layout
    VkDescriptorSetLayoutBinding samplerLayoutBinding{};
    samplerLayoutBinding.binding = 0;
    samplerLayoutBinding.descriptorCount = 1;
    samplerLayoutBinding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    samplerLayoutBinding.pImmutableSamplers = &layer.mVideoSampler;
    samplerLayoutBinding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 1;
    layoutInfo.pBindings = &samplerLayoutBinding;

    if (vkCreateDescriptorSetLayout(mDevice, &layoutInfo, nullptr, &layer.mVideoDescriptorSetLayout) != VK_SUCCESS) {
        LOGE("Failed to create descriptor set layout");
        return false;
    }

    // 4. Create Descriptor Pool and Set
    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSize.descriptorCount = 3; // Multi-planar YCbCr combined samplers can consume three descriptors.

    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    poolInfo.maxSets = 1;

    if (vkCreateDescriptorPool(mDevice, &poolInfo, nullptr, &layer.mVideoDescriptorPool) != VK_SUCCESS) {
        LOGE("Failed to create descriptor pool");
        return false;
    }

    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = layer.mVideoDescriptorPool;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &layer.mVideoDescriptorSetLayout;

    if (vkAllocateDescriptorSets(mDevice, &allocInfo, &layer.mVideoDescriptorSet) != VK_SUCCESS) {
        LOGE("Failed to allocate descriptor set");
        return false;
    }

    // 5. Create Pipeline Layout
    VkPushConstantRange pushConstantRange{};
    pushConstantRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
    pushConstantRange.offset = 0;
    pushConstantRange.size = sizeof(LayerTransform);

    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &layer.mVideoDescriptorSetLayout;
    pipelineLayoutInfo.pushConstantRangeCount = 1;
    pipelineLayoutInfo.pPushConstantRanges = &pushConstantRange;

    if (vkCreatePipelineLayout(mDevice, &pipelineLayoutInfo, nullptr, &layer.mVideoPipelineLayout) != VK_SUCCESS) {
        LOGE("Failed to create pipeline layout");
        return false;
    }

    // 6. Create Graphics Pipeline
    auto vertShaderCode = readAsset(mAssetManager, "shaders/video.vert.spv");
    auto fragShaderCode = readAsset(mAssetManager, "shaders/video.frag.spv");

    VkShaderModule vertShaderModule = createShaderModule(mDevice, vertShaderCode);
    VkShaderModule fragShaderModule = createShaderModule(mDevice, fragShaderCode);
    if (!vertShaderModule || !fragShaderModule) {
        if (vertShaderModule) vkDestroyShaderModule(mDevice, vertShaderModule, nullptr);
        if (fragShaderModule) vkDestroyShaderModule(mDevice, fragShaderModule, nullptr);
        return false;
    }

    VkPipelineShaderStageCreateInfo vertShaderStageInfo{};
    vertShaderStageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    vertShaderStageInfo.stage = VK_SHADER_STAGE_VERTEX_BIT;
    vertShaderStageInfo.module = vertShaderModule;
    vertShaderStageInfo.pName = "main";

    VkPipelineShaderStageCreateInfo fragShaderStageInfo{};
    fragShaderStageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    fragShaderStageInfo.stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    fragShaderStageInfo.module = fragShaderModule;
    fragShaderStageInfo.pName = "main";

    VkPipelineShaderStageCreateInfo shaderStages[] = {vertShaderStageInfo, fragShaderStageInfo};

    VkPipelineVertexInputStateCreateInfo vertexInputInfo{};
    vertexInputInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

    VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
    inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    inputAssembly.primitiveRestartEnable = VK_FALSE;

    VkViewport viewport{};
    viewport.x = 0.0f;
    viewport.y = 0.0f;
    viewport.width = (float)mSwapchainExtent.width;
    viewport.height = (float)mSwapchainExtent.height;
    viewport.minDepth = 0.0f;
    viewport.maxDepth = 1.0f;

    VkRect2D scissor{};
    scissor.offset = {0, 0};
    scissor.extent = mSwapchainExtent;

    VkPipelineViewportStateCreateInfo viewportState{};
    viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    viewportState.viewportCount = 1;
    viewportState.pViewports = &viewport;
    viewportState.scissorCount = 1;
    viewportState.pScissors = &scissor;

    VkPipelineRasterizationStateCreateInfo rasterizer{};
    rasterizer.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    rasterizer.depthClampEnable = VK_FALSE;
    rasterizer.rasterizerDiscardEnable = VK_FALSE;
    rasterizer.polygonMode = VK_POLYGON_MODE_FILL;
    rasterizer.lineWidth = 1.0f;
    rasterizer.cullMode = VK_CULL_MODE_NONE;
    rasterizer.frontFace = VK_FRONT_FACE_CLOCKWISE;

    VkPipelineMultisampleStateCreateInfo multisampling{};
    multisampling.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    multisampling.sampleShadingEnable = VK_FALSE;
    multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineColorBlendAttachmentState colorBlendAttachment{};
    colorBlendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    colorBlendAttachment.blendEnable = VK_TRUE;
    colorBlendAttachment.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
    colorBlendAttachment.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    colorBlendAttachment.colorBlendOp = VK_BLEND_OP_ADD;
    colorBlendAttachment.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
    colorBlendAttachment.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    colorBlendAttachment.alphaBlendOp = VK_BLEND_OP_ADD;

    VkPipelineColorBlendStateCreateInfo colorBlending{};
    colorBlending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    colorBlending.logicOpEnable = VK_FALSE;
    colorBlending.attachmentCount = 1;
    colorBlending.pAttachments = &colorBlendAttachment;

    const VkDynamicState dynamicStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamicState{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    dynamicState.dynamicStateCount = 2;
    dynamicState.pDynamicStates = dynamicStates;
    VkGraphicsPipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pipelineInfo.stageCount = 2;
    pipelineInfo.pStages = shaderStages;
    pipelineInfo.pVertexInputState = &vertexInputInfo;
    pipelineInfo.pInputAssemblyState = &inputAssembly;
    pipelineInfo.pViewportState = &viewportState;
    pipelineInfo.pRasterizationState = &rasterizer;
    pipelineInfo.pMultisampleState = &multisampling;
    pipelineInfo.pColorBlendState = &colorBlending;
    pipelineInfo.pDynamicState = &dynamicState;
    pipelineInfo.layout = layer.mVideoPipelineLayout;
    pipelineInfo.renderPass = mOffscreenRenderPass;
    pipelineInfo.subpass = 0;

    if (vkCreateGraphicsPipelines(mDevice, mPipelineCache, 1, &pipelineInfo, nullptr, &layer.mVideoPipeline) != VK_SUCCESS) {
        LOGE("Failed to create graphics pipeline");
        vkDestroyShaderModule(mDevice, fragShaderModule, nullptr);
        vkDestroyShaderModule(mDevice, vertShaderModule, nullptr);
        return false;
    }

    vkDestroyShaderModule(mDevice, fragShaderModule, nullptr);
    vkDestroyShaderModule(mDevice, vertShaderModule, nullptr);

    layer.mVideoPipelineCreated = true;
    LOGI("Video pipeline created successfully");
    return true;
}




bool VulkanRenderer::createOffscreenTarget() {
    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.extent.width = mCompWidth;
    imageInfo.extent.height = mCompHeight;
    imageInfo.extent.depth = 1;
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    imageInfo.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateImage(mDevice, &imageInfo, nullptr, &mOffscreenImage) != VK_SUCCESS) return false;

    VkMemoryRequirements memRequirements;
    vkGetImageMemoryRequirements(mDevice, mOffscreenImage, &memRequirements);

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memRequirements.size;

    VkPhysicalDeviceMemoryProperties memProperties;
    vkGetPhysicalDeviceMemoryProperties(mPhysicalDevice, &memProperties);
    for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
        if ((memRequirements.memoryTypeBits & (1 << i)) && (memProperties.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) == VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) {
            allocInfo.memoryTypeIndex = i;
            break;
        }
    }
    if (vkAllocateMemory(mDevice, &allocInfo, nullptr, &mOffscreenMemory) != VK_SUCCESS) return false;
    vkBindImageMemory(mDevice, mOffscreenImage, mOffscreenMemory, 0);

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = mOffscreenImage;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.baseMipLevel = 0;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.baseArrayLayer = 0;
    viewInfo.subresourceRange.layerCount = 1;
    if (vkCreateImageView(mDevice, &viewInfo, nullptr, &mOffscreenImageView) != VK_SUCCESS) return false;

    VkAttachmentDescription colorAttachment{};
    colorAttachment.format = VK_FORMAT_R8G8B8A8_UNORM;
    colorAttachment.samples = VK_SAMPLE_COUNT_1_BIT;
    colorAttachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    colorAttachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    colorAttachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    colorAttachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    colorAttachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    colorAttachment.finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

    VkAttachmentReference colorAttachmentRef{};
    colorAttachmentRef.attachment = 0;
    colorAttachmentRef.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &colorAttachmentRef;

    VkRenderPassCreateInfo renderPassInfo{};
    renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    renderPassInfo.attachmentCount = 1;
    renderPassInfo.pAttachments = &colorAttachment;
    renderPassInfo.subpassCount = 1;
    renderPassInfo.pSubpasses = &subpass;
    VkSubpassDependency dependencies[2]{};
    dependencies[0].srcSubpass=VK_SUBPASS_EXTERNAL;
    dependencies[0].dstSubpass=0;
    dependencies[0].srcStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT;
    dependencies[0].dstStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependencies[0].srcAccessMask=VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT;
    dependencies[0].dstAccessMask=VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    dependencies[1].srcSubpass=0;
    dependencies[1].dstSubpass=VK_SUBPASS_EXTERNAL;
    dependencies[1].srcStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependencies[1].dstStageMask=VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependencies[1].srcAccessMask=VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    dependencies[1].dstAccessMask=VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    renderPassInfo.dependencyCount=2;
    renderPassInfo.pDependencies=dependencies;

    if (vkCreateRenderPass(mDevice, &renderPassInfo, nullptr, &mOffscreenRenderPass) != VK_SUCCESS) return false;

    colorAttachment.loadOp = VK_ATTACHMENT_LOAD_OP_LOAD;
    colorAttachment.initialLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    if (vkCreateRenderPass(mDevice, &renderPassInfo, nullptr, &mLoadRenderPass) != VK_SUCCESS) return false;
    VkFramebufferCreateInfo framebufferInfo{};
    framebufferInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
    framebufferInfo.renderPass = mOffscreenRenderPass;
    framebufferInfo.attachmentCount = 1;
    framebufferInfo.pAttachments = &mOffscreenImageView;
    framebufferInfo.width = mCompWidth;
    framebufferInfo.height = mCompHeight;
    framebufferInfo.layers = 1;

    if (vkCreateFramebuffer(mDevice, &framebufferInfo, nullptr, &mOffscreenFramebuffer) != VK_SUCCESS) return false;
    return true;
}

void VulkanRenderer::cleanupOffscreenTarget() {
    if (mReadbackMapped) vkUnmapMemory(mDevice,mReadbackMemory);
    mReadbackMapped = nullptr;
    if (mReadback) vkDestroyBuffer(mDevice,mReadback,nullptr);
    if (mReadbackMemory) vkFreeMemory(mDevice,mReadbackMemory,nullptr);
    mReadback=VK_NULL_HANDLE; mReadbackMemory=VK_NULL_HANDLE; mReadbackSize=0;
    if (mLoadRenderPass) vkDestroyRenderPass(mDevice,mLoadRenderPass,nullptr);
    mLoadRenderPass=VK_NULL_HANDLE;
    if (mOffscreenFramebuffer != VK_NULL_HANDLE) {
        vkDestroyFramebuffer(mDevice, mOffscreenFramebuffer, nullptr);
        mOffscreenFramebuffer = VK_NULL_HANDLE;
    }
    if (mOffscreenRenderPass != VK_NULL_HANDLE) {
        vkDestroyRenderPass(mDevice, mOffscreenRenderPass, nullptr);
        mOffscreenRenderPass = VK_NULL_HANDLE;
    }
    if (mOffscreenImageView != VK_NULL_HANDLE) {
        vkDestroyImageView(mDevice, mOffscreenImageView, nullptr);
        mOffscreenImageView = VK_NULL_HANDLE;
    }
    if (mOffscreenImage != VK_NULL_HANDLE) {
        vkDestroyImage(mDevice, mOffscreenImage, nullptr);
        mOffscreenImage = VK_NULL_HANDLE;
    }
    if (mOffscreenMemory != VK_NULL_HANDLE) {
        vkFreeMemory(mDevice, mOffscreenMemory, nullptr);
        mOffscreenMemory = VK_NULL_HANDLE;
    }
}




// Every entry point below is confined to the same preview worker as render().
bool VulkanRenderer::beginFrame(int width, int height, int presentationWidth, int presentationHeight) {
    mReadbackValid=false;
    if (!mInitialized || width <= 0 || height <= 0) return false;
    mPresentationWidth = presentationWidth > 0 ? presentationWidth : width;
    mPresentationHeight = presentationHeight > 0 ? presentationHeight : height;
    mFirstBatch = true;
    mDrawOrder.clear();
    if (width != mCompWidth || height != mCompHeight) {
        LOGI("Offscreen target resize %dx%d -> %dx%d (presentation %dx%d)",
             mCompWidth, mCompHeight, width, height, mPresentationWidth, mPresentationHeight);
        // Target recreation is serialized on the owner worker; pause alone is not a GPU fence.
        if (vkDeviceWaitIdle(mDevice) != VK_SUCCESS) return false;
        releaseLayers();
        cleanupOffscreenTarget();
        mCompWidth = width; mCompHeight = height;
        if (!createOffscreenTarget()) return false;
    }
    return true;
}
bool VulkanRenderer::setFrameLayer(int64_t id, AHardwareBuffer* buffer, const float* matrix, float opacity) {
    if (!mInitialized || !buffer || mDrawOrder.size() >= 4) return false;
    if (mLayers.find(id) == mLayers.end() && mLayers.size() >= 4) {
        auto victim = mLayers.end();
        for (auto it=mLayers.begin();it!=mLayers.end();++it) {
            if (std::find(mDrawOrder.begin(),mDrawOrder.end(),it->first)!=mDrawOrder.end()) continue;
            if(victim==mLayers.end() || it->second.lastUse<victim->second.lastUse) victim=it;
        }
        if(victim==mLayers.end()) return false;
        cleanupVideoImage(victim->second);cleanupVideoPipeline(victim->second);
        if(victim->second.buffer) AHardwareBuffer_release(victim->second.buffer);
        mLayers.erase(victim);
    }
    auto& layer = mLayers[id];
    layer.lastUse=++mUseCounter;
    if (layer.buffer != buffer) {
        cleanupVideoImage(layer);
        if (layer.buffer) AHardwareBuffer_release(layer.buffer);
        layer.buffer = buffer;
        AHardwareBuffer_acquire(buffer);
        layer.transitioned = false;
        if (!createHardwareBufferImage(layer, buffer)) {
            cleanupVideoImage(layer);
            AHardwareBuffer_release(layer.buffer);
            layer.buffer = nullptr;
            return false;
        }
    }
    std::copy(matrix, matrix + 16, layer.transform.matrix);
    layer.transform.opacity = opacity;
    mDrawOrder.push_back(id);
    return true;
}
bool VulkanRenderer::finishFrame() {
    if (!mInitialized) return false;
    if ((!mDrawOrder.empty() || mFirstBatch) && !flushBatch()) return false;
    if (mWindow) { return render() && vkQueueWaitIdle(mPresentQueue) == VK_SUCCESS; }
    return true;
}
bool VulkanRenderer::beginWork() {
    if (vkWaitForFences(mDevice,1,&mWorkFence,VK_TRUE,5000000000ULL) != VK_SUCCESS) return false;
    vkResetCommandBuffer(mWorkCommand,0);
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    return vkBeginCommandBuffer(mWorkCommand,&begin)==VK_SUCCESS;
}
bool VulkanRenderer::submitWork() {
    if (vkEndCommandBuffer(mWorkCommand)!=VK_SUCCESS) return false;
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount=1; submit.pCommandBuffers=&mWorkCommand;
    vkResetFences(mDevice,1,&mWorkFence);
    if (vkQueueSubmit(mGraphicsQueue,1,&submit,mWorkFence)!=VK_SUCCESS) return false;
    return vkWaitForFences(mDevice,1,&mWorkFence,VK_TRUE,5000000000ULL)==VK_SUCCESS;
}
bool VulkanRenderer::flushBatch() {
    if (!beginWork()) return false;
    auto commandBuffer=mWorkCommand;
    VkRenderPassBeginInfo renderPassInfo{};
    renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    renderPassInfo.renderPass = mFirstBatch ? mOffscreenRenderPass : mLoadRenderPass;
    renderPassInfo.framebuffer = mOffscreenFramebuffer;
    renderPassInfo.renderArea.offset = {0, 0};
    renderPassInfo.renderArea.extent = {(uint32_t)mCompWidth, (uint32_t)mCompHeight};

    VkClearValue clearColor = {{{0.0f, 0.0f, 0.0f, 1.0f}}};
    renderPassInfo.clearValueCount = 1;
    renderPassInfo.pClearValues = &clearColor;

    for (auto id : mDrawOrder) {
        auto& layer = mLayers.at(id);
        if (layer.transitioned) continue;
        layer.transitioned = true;
        VkImageMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = layer.mVideoImage;
        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.baseMipLevel = 0;
        barrier.subresourceRange.levelCount = 1;
        barrier.subresourceRange.baseArrayLayer = 0;
        barrier.subresourceRange.layerCount = 1;
        barrier.srcAccessMask = 0;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;

        vkCmdPipelineBarrier(
            commandBuffer,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0,
            0, nullptr,
            0, nullptr,
            1, &barrier
        );
    }

    vkCmdBeginRenderPass(commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

    for (auto id : mDrawOrder) {
        auto& layer = mLayers.at(id);
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, layer.mVideoPipeline);

        VkViewport viewport{};
        viewport.minDepth = 0.0f;
        viewport.maxDepth = 1.0f;

        viewport.x = 0.0f;
        viewport.y = 0.0f;
        viewport.width = (float)mCompWidth;
        viewport.height = (float)mCompHeight;

        VkRect2D scissor{};
        scissor.offset.x = (int32_t)viewport.x;
        scissor.offset.y = (int32_t)viewport.y;
        scissor.extent.width = (uint32_t)viewport.width;
        scissor.extent.height = (uint32_t)viewport.height;

        vkCmdSetViewport(commandBuffer, 0, 1, &viewport);
        vkCmdSetScissor(commandBuffer, 0, 1, &scissor);

        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, layer.mVideoPipelineLayout, 0, 1, &layer.mVideoDescriptorSet, 0, nullptr);
        vkCmdPushConstants(commandBuffer, layer.mVideoPipelineLayout, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(LayerTransform), &layer.transform);
        vkCmdDraw(commandBuffer, 6, 1, 0, 0);
    }

    vkCmdEndRenderPass(commandBuffer);
    if (!submitWork()) return false;
    mFirstBatch=false;
    mDrawOrder.clear();
    return true;
}
void VulkanRenderer::releaseLayers() {
    if (mDevice != VK_NULL_HANDLE) vkDeviceWaitIdle(mDevice);
    for (auto& entry : mLayers) {
        cleanupVideoImage(entry.second); cleanupVideoPipeline(entry.second);
        if (entry.second.buffer) AHardwareBuffer_release(entry.second.buffer);
    }
    mLayers.clear(); mDrawOrder.clear();
}

bool VulkanRenderer::readbackRgba() {
    if (!mOffscreenImage || mCompWidth<=0 || mCompHeight<=0) return false;
    if(mReadbackValid && mReadbackMapped) return true;
    const VkDeviceSize bytes=static_cast<VkDeviceSize>(mCompWidth)*mCompHeight*4;
    if (!mReadback) {
        VkBufferCreateInfo info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        info.size=bytes; info.usage=VK_BUFFER_USAGE_TRANSFER_DST_BIT; info.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
        if(vkCreateBuffer(mDevice,&info,nullptr,&mReadback)!=VK_SUCCESS) return false;
        VkMemoryRequirements req; vkGetBufferMemoryRequirements(mDevice,mReadback,&req);
        VkPhysicalDeviceMemoryProperties props; vkGetPhysicalDeviceMemoryProperties(mPhysicalDevice,&props);
        uint32_t type=UINT32_MAX;
        for(uint32_t i=0;i<props.memoryTypeCount;i++) if((req.memoryTypeBits&(1u<<i)) &&
            (props.memoryTypes[i].propertyFlags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) ==
            (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) { type=i; break; }
        if(type==UINT32_MAX) return false;
        VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; alloc.allocationSize=req.size; alloc.memoryTypeIndex=type;
        if(vkAllocateMemory(mDevice,&alloc,nullptr,&mReadbackMemory)!=VK_SUCCESS) return false;
        if(vkBindBufferMemory(mDevice,mReadback,mReadbackMemory,0)!=VK_SUCCESS) return false;
        if(vkMapMemory(mDevice,mReadbackMemory,0,bytes,0,&mReadbackMapped)!=VK_SUCCESS) return false;
        mReadbackSize=bytes;
    }
    if(!mReadbackMapped || mReadbackSize<bytes) return false;
    if(!beginWork()) return false;
    // Both clear/load composition render passes finish in TRANSFER_SRC_OPTIMAL;
    // preview blits read that layout without changing it. Keep it for the next load pass.
    // This barrier makes the render writes visible to this independent readback submission.
    VkImageMemoryBarrier imageBarrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    imageBarrier.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    imageBarrier.newLayout=VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    imageBarrier.srcAccessMask=VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT;
    imageBarrier.dstAccessMask=VK_ACCESS_TRANSFER_READ_BIT;
    imageBarrier.srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
    imageBarrier.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
    imageBarrier.image=mOffscreenImage;
    imageBarrier.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    vkCmdPipelineBarrier(mWorkCommand,VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,nullptr,0,nullptr,1,&imageBarrier);
    VkBufferImageCopy region{}; region.imageSubresource.aspectMask=VK_IMAGE_ASPECT_COLOR_BIT;
    region.imageSubresource.layerCount=1; region.imageExtent={static_cast<uint32_t>(mCompWidth),static_cast<uint32_t>(mCompHeight),1};
    vkCmdCopyImageToBuffer(mWorkCommand,mOffscreenImage,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,mReadback,1,&region);
    VkBufferMemoryBarrier barrier{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
    barrier.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT; barrier.dstAccessMask=VK_ACCESS_HOST_READ_BIT;
    barrier.srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED; barrier.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
    barrier.buffer=mReadback; barrier.size=bytes;
    vkCmdPipelineBarrier(mWorkCommand,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,0,nullptr,1,&barrier,0,nullptr);
    if(!submitWork()) return false;
    mReadbackValid=true;
    return true;
}

int64_t VulkanRenderer::readPreviewPixel(float nx, float ny) {
    if (!(nx>=0 && nx<1 && ny>=0 && ny<1) || mPreviewW<=0 || mPreviewH<=0) return -1;
    const double x=nx*mPreviewBufferW-mPreviewX, y=ny*mPreviewBufferH-mPreviewY;
    if(x<0 || y<0 || x>=mPreviewW || y>=mPreviewH) return -1;
    const int cx=std::min(mCompWidth-1,static_cast<int>(x*mCompWidth/mPreviewW));
    const int cy=std::min(mCompHeight-1,static_cast<int>(y*mCompHeight/mPreviewH));
    if(!readbackRgba()) return -1;
    const auto* p=static_cast<const uint8_t*>(mReadbackMapped)+(static_cast<size_t>(cy)*mCompWidth+cx)*4;
    return (static_cast<int64_t>(p[3])<<24) | (static_cast<int64_t>(p[0])<<16) | (p[1]<<8) | p[2];
}

bool VulkanRenderer::copyYuv(uint8_t* y, uint8_t* u, uint8_t* v, int yr, int ur, int vr, int up, int vp) {
    if(!readbackRgba()) return false;
    const auto* rgba=static_cast<const uint8_t*>(mReadbackMapped);
    auto clamp=[](int a){ return static_cast<uint8_t>(std::max(0,std::min(255,a))); };
    for(int row=0;row<mCompHeight;row+=2) for(int col=0;col<mCompWidth;col+=2) {
        int r=0,g=0,b=0;
        for(int dy=0;dy<2;dy++) for(int dx=0;dx<2;dx++) {
            auto* p=rgba+((row+dy)*mCompWidth+col+dx)*4;
            y[(row+dy)*yr+col+dx]=clamp(16+((11966*p[0]+40254*p[1]+4064*p[2]+32768)>>16));
            r+=p[0];g+=p[1];b+=p[2];
        }
        r=(r+2)/4;g=(g+2)/4;b=(b+2)/4;
        u[(row/2)*ur+(col/2)*up]=clamp(128+((-6596*r-22188*g+28784*b+32768)>>16));
        v[(row/2)*vr+(col/2)*vp]=clamp(128+((28784*r-26145*g-2639*b+32768)>>16));
    }
    return true;
}
