#include "vulkan_renderer.h"
#include "logger.h"
#include <vulkan/vulkan_android.h>
#include <stdexcept>
#include <string>

VulkanRenderer::VulkanRenderer() {
    for (int i = 0; i < 16; i++) {
        mLayerTransform.matrix[i] = (i % 5 == 0) ? 1.0f : 0.0f;
    }
    mLayerTransform.opacity = 1.0f;
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
    mAssetManager = assetManager;
    LOGI("Initializing Vulkan for window: %p", window);

    try {
        if (!createInstance()) return false;

    VkAndroidSurfaceCreateInfoKHR surfaceInfo{};
    surfaceInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    surfaceInfo.window = mWindow;
    if (vkCreateAndroidSurfaceKHR(mInstance, &surfaceInfo, nullptr, &mSurface) != VK_SUCCESS) {
        LOGE("Failed to create Android surface");
        return false;
    }

    if (!selectPhysicalDevice()) return false;
        if (!createDevice()) return false;
        if (!createOffscreenTarget()) return false;
        if (!createSwapchain()) return false;
        if (!createCommandPool()) return false;
        if (!createCommandBuffers()) return false;
        if (!createSyncObjects()) return false;

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
    if (!mInitialized || width == 0 || height == 0) return;
    LOGI("VulkanRenderer resize to: %dx%d", width, height);
    mWidth = width;
    mHeight = height;
    
    if (mDevice != VK_NULL_HANDLE && mDevice != (VkDevice)1) {
        vkDeviceWaitIdle(mDevice);
        cleanupSwapchain();
        createSwapchain();
        if (mCommandBuffers.size() > 0) {
            vkFreeCommandBuffers(mDevice, mCommandPool, (uint32_t)mCommandBuffers.size(), mCommandBuffers.data());
        }
        createCommandBuffers();
    }
}

void VulkanRenderer::render() {
    LOGI("VulkanRenderer::render() executing. mInitialized=%d, mDevice=%p", mInitialized, mDevice);
    if (!mInitialized || mDevice == VK_NULL_HANDLE || mDevice == (VkDevice)1) return;

    vkWaitForFences(mDevice, 1, &mInFlightFence, VK_TRUE, UINT64_MAX);

    uint32_t imageIndex;
    VkResult result = vkAcquireNextImageKHR(mDevice, mSwapchain, UINT64_MAX, mImageAvailableSemaphore, VK_NULL_HANDLE, &imageIndex);

    if (result == VK_ERROR_OUT_OF_DATE_KHR) {
        resize(mWidth, mHeight);
        return;
    } else if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
        LOGE("Failed to acquire swapchain image!");
        return;
    }

    vkResetFences(mDevice, 1, &mInFlightFence);

    AHardwareBuffer* newBuffer = mStagedBuffer.exchange(nullptr);
    int64_t newGeneration = mStagedGeneration.load();
    int newCropWidth = mStagedCropWidth.load();
    int newCropHeight = mStagedCropHeight.load();

    if (newBuffer) {
        if (mCurrentRenderingBuffer) {
            cleanupVideoImage();
            AHardwareBuffer_release(mCurrentRenderingBuffer);
            // Advance mLastConsumedGeneration for the buffer we just finished rendering
            if (mCurrentRenderingGeneration >= 0) {
                int64_t currentLast = mLastConsumedGeneration.load();
                while (mCurrentRenderingGeneration > currentLast) {
                    if (mLastConsumedGeneration.compare_exchange_weak(currentLast, mCurrentRenderingGeneration)) {
                        break;
                    }
                }
            }
        }

        mCurrentRenderingBuffer = newBuffer;
        mCurrentRenderingGeneration = newGeneration;
        mCurrentCropWidth = newCropWidth;
        mCurrentCropHeight = newCropHeight;
        
        createHardwareBufferImage(mCurrentRenderingBuffer);
    }

    VkCommandBuffer commandBuffer = mCommandBuffers[imageIndex];
    vkResetCommandBuffer(commandBuffer, 0);

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;

    if (vkBeginCommandBuffer(commandBuffer, &beginInfo) != VK_SUCCESS) {
        LOGE("Failed to begin recording command buffer!");
        return;
    }

    VkRenderPassBeginInfo renderPassInfo{};
    renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    renderPassInfo.renderPass = mOffscreenRenderPass;
    renderPassInfo.framebuffer = mOffscreenFramebuffer;
    renderPassInfo.renderArea.offset = {0, 0};
    renderPassInfo.renderArea.extent = {(uint32_t)mCompWidth, (uint32_t)mCompHeight};

    VkClearValue clearColor = {{{0.0f, 0.0f, 0.0f, 1.0f}}};
    renderPassInfo.clearValueCount = 1;
    renderPassInfo.pClearValues = &clearColor;

    if (mVideoImage != VK_NULL_HANDLE) {
        VkImageMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = mVideoImage;
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

    if (mVideoPipelineCreated && mVideoDescriptorSet != VK_NULL_HANDLE) {
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, mVideoPipeline);
        
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

        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, mVideoPipelineLayout, 0, 1, &mVideoDescriptorSet, 0, nullptr);
        vkCmdPushConstants(commandBuffer, mVideoPipelineLayout, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(mLayerTransform), &mLayerTransform);
        vkCmdDraw(commandBuffer, 6, 1, 0, 0);
    }

    vkCmdEndRenderPass(commandBuffer);

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

    float compAspect = (float)mCompWidth / (float)mCompHeight;
    float screenAspect = (float)mSwapchainExtent.width / (float)mSwapchainExtent.height;
    int dstW = mSwapchainExtent.width;
    int dstH = mSwapchainExtent.height;
    int dstX = 0;
    int dstY = 0;
    if (compAspect > screenAspect) {
        dstH = mSwapchainExtent.width / compAspect;
        dstY = (mSwapchainExtent.height - dstH) / 2;
    } else {
        dstW = mSwapchainExtent.height * compAspect;
        dstX = (mSwapchainExtent.width - dstW) / 2;
    }

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
        return;
    }

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;

    VkSemaphore waitSemaphores[] = {mImageAvailableSemaphore};
    VkPipelineStageFlags waitStages[] = {VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};
    submitInfo.waitSemaphoreCount = 1;
    submitInfo.pWaitSemaphores = waitSemaphores;
    submitInfo.pWaitDstStageMask = waitStages;

    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer;

    VkSemaphore signalSemaphores[] = {mRenderFinishedSemaphore};
    submitInfo.signalSemaphoreCount = 1;
    submitInfo.pSignalSemaphores = signalSemaphores;

    if (vkQueueSubmit(mGraphicsQueue, 1, &submitInfo, mInFlightFence) != VK_SUCCESS) {
        LOGE("Failed to submit draw command buffer!");
        return;
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
    } else if (result != VK_SUCCESS) {
        LOGE("Failed to present swapchain image!");
    }
}

void VulkanRenderer::stageHardwareBuffer(AHardwareBuffer* buffer, int64_t generationId, int cropWidth, int cropHeight) {
    if (!mInitialized) {
        LOGE("Cannot stage hardware buffer: Vulkan not initialized");
        return;
    }
    
    if (buffer) {
        AHardwareBuffer_acquire(buffer);
    }
    
    AHardwareBuffer* oldStaged = mStagedBuffer.exchange(buffer);
    int64_t oldGeneration = mStagedGeneration.exchange(generationId);
    
    mStagedCropWidth.store(cropWidth);
    mStagedCropHeight.store(cropHeight);
    
    if (oldStaged) {
        AHardwareBuffer_release(oldStaged);
        
        // Atomically advance mLastConsumedGeneration if we dropped a frame
        int64_t currentLast = mLastConsumedGeneration.load();
        while (oldGeneration > currentLast) {
            if (mLastConsumedGeneration.compare_exchange_weak(currentLast, oldGeneration)) {
                break;
            }
        }
    }
    
    LOGI("VulkanRenderer: Successfully staged AHardwareBuffer (gen: %lld) crop: %dx%d", (long long)generationId, cropWidth, cropHeight);
}

int64_t VulkanRenderer::getLastConsumedGeneration() const {
    return mLastConsumedGeneration.load();
}

void VulkanRenderer::cleanup() {
    if (!mInitialized) return;
    LOGI("Cleaning up Vulkan resources");

    cleanupVideoImage();
    cleanupVideoPipeline();
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
    if (mCommandPool != VK_NULL_HANDLE) {
        vkDestroyCommandPool(mDevice, mCommandPool, nullptr);
        mCommandPool = VK_NULL_HANDLE;
    }
    if (mDevice != VK_NULL_HANDLE) {
        vkDestroyDevice(mDevice, nullptr);
        mDevice = VK_NULL_HANDLE;
    }
    if (mInstance != VK_NULL_HANDLE) {
        if (mSurface != VK_NULL_HANDLE) {
            // Android extensions: vkDestroySurfaceKHR
            // Normally load via vkGetInstanceProcAddr or standard link
            // vkDestroySurfaceKHR(mInstance, mSurface, nullptr);
            mSurface = VK_NULL_HANDLE;
        }
        vkDestroyInstance(mInstance, nullptr);
        mInstance = VK_NULL_HANDLE;
    }

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
        mInstance = (VkInstance)1; // Mock handle for skeleton testing on non-Vulkan compile targets if needed
    } else {
        LOGI("VkInstance created successfully at %p", mInstance);
    }
    return true;
}

bool VulkanRenderer::selectPhysicalDevice() {
    LOGI("Vulkan: Selecting VkPhysicalDevice...");
    if (mInstance == (VkInstance)1) {
        mPhysicalDevice = (VkPhysicalDevice)1;
        LOGI("Vulkan: Selected mock PhysicalDevice");
        return true;
    }

    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(mInstance, &deviceCount, nullptr);
    if (deviceCount == 0) {
        LOGW("No physical devices with Vulkan support found. Using mock physical device.");
        mPhysicalDevice = (VkPhysicalDevice)1;
        return true;
    }

    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(mInstance, &deviceCount, devices.data());
    mPhysicalDevice = devices[0];
    LOGI("Vulkan: Selected physical device: %p", mPhysicalDevice);
    return true;
}

bool VulkanRenderer::createDevice() {
    LOGI("Vulkan: Creating VkDevice...");
    if (mPhysicalDevice == (VkPhysicalDevice)1) {
        mDevice = (VkDevice)1;
        LOGI("Vulkan: Created mock logical Device");
        return true;
    }

    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueCreateInfo{};
    queueCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueCreateInfo.queueFamilyIndex = 0;
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
    createInfo.queueCreateInfoCount = 1;
    createInfo.pQueueCreateInfos = &queueCreateInfo;
    createInfo.enabledExtensionCount = static_cast<uint32_t>(deviceExtensions.size());
    createInfo.ppEnabledExtensionNames = deviceExtensions.data();

    VkResult result = vkCreateDevice(mPhysicalDevice, &createInfo, nullptr, &mDevice);
    if (result != VK_SUCCESS) {
        LOGE("vkCreateDevice failed with code: %d", result);
        mDevice = (VkDevice)1; // Mock
    } else {
        LOGI("VkDevice created successfully at %p", mDevice);
        vkGetDeviceQueue(mDevice, 0, 0, &mGraphicsQueue);
        mPresentQueue = mGraphicsQueue;
    }
    return true;
}

bool VulkanRenderer::createSwapchain() {
    VkSurfaceCapabilitiesKHR capabilities;
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(mPhysicalDevice, mSurface, &capabilities);

    uint32_t formatCount;
    vkGetPhysicalDeviceSurfaceFormatsKHR(mPhysicalDevice, mSurface, &formatCount, nullptr);
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
    poolInfo.queueFamilyIndex = 0;

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

void VulkanRenderer::cleanupVideoImage() {
    if (mDevice == VK_NULL_HANDLE || mDevice == (VkDevice)1) return;

    if (mVideoImageView != VK_NULL_HANDLE) {
        vkDestroyImageView(mDevice, mVideoImageView, nullptr);
        mVideoImageView = VK_NULL_HANDLE;
    }
    if (mVideoImage != VK_NULL_HANDLE) {
        vkDestroyImage(mDevice, mVideoImage, nullptr);
        mVideoImage = VK_NULL_HANDLE;
    }
    if (mVideoMemory != VK_NULL_HANDLE) {
        vkFreeMemory(mDevice, mVideoMemory, nullptr);
        mVideoMemory = VK_NULL_HANDLE;
    }
}

bool VulkanRenderer::createHardwareBufferImage(AHardwareBuffer* buffer) {
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
    if (formatProps.format != VK_FORMAT_UNDEFINED) {
        LOGW("Hardware buffer format is not UNDEFINED (is %d). Attempting to use external format anyway.", formatProps.format);
    }

    if (!createVideoPipelineOnce(formatProps)) return false;

    // Create Image
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(buffer, &desc);

    VkExternalFormatANDROID externalFormat{};
    externalFormat.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
    externalFormat.externalFormat = formatProps.externalFormat;

    VkExternalMemoryImageCreateInfo extMemImageInfo{};
    extMemImageInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    extMemImageInfo.pNext = &externalFormat;
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

    if (vkCreateImage(mDevice, &imageInfo, nullptr, &mVideoImage) != VK_SUCCESS) return false;

    // Import Memory
    VkImportAndroidHardwareBufferInfoANDROID importInfo{};
    importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    importInfo.buffer = buffer;

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

    if (vkAllocateMemory(mDevice, &allocInfo, nullptr, &mVideoMemory) != VK_SUCCESS) return false;
    if (vkBindImageMemory(mDevice, mVideoImage, mVideoMemory, 0) != VK_SUCCESS) return false;

    // Create Image View
    VkSamplerYcbcrConversionInfo ycbcrConversionInfo{};
    ycbcrConversionInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO;
    ycbcrConversionInfo.conversion = mYcbcrConversion;

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.pNext = &ycbcrConversionInfo;
    viewInfo.image = mVideoImage;
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

    if (vkCreateImageView(mDevice, &viewInfo, nullptr, &mVideoImageView) != VK_SUCCESS) return false;

    // Update Descriptor Set
    VkDescriptorImageInfo descImageInfo{};
    descImageInfo.sampler = mVideoSampler;
    descImageInfo.imageView = mVideoImageView;
    descImageInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    VkWriteDescriptorSet writeSet{};
    writeSet.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writeSet.dstSet = mVideoDescriptorSet;
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

void VulkanRenderer::cleanupVideoPipeline() {
    if (mDevice == VK_NULL_HANDLE || mDevice == (VkDevice)1) return;
    if (mVideoPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(mDevice, mVideoPipeline, nullptr);
        mVideoPipeline = VK_NULL_HANDLE;
    }
    if (mVideoPipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(mDevice, mVideoPipelineLayout, nullptr);
        mVideoPipelineLayout = VK_NULL_HANDLE;
    }
    if (mVideoDescriptorSetLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(mDevice, mVideoDescriptorSetLayout, nullptr);
        mVideoDescriptorSetLayout = VK_NULL_HANDLE;
    }
    if (mVideoDescriptorPool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(mDevice, mVideoDescriptorPool, nullptr);
        mVideoDescriptorPool = VK_NULL_HANDLE;
    }
    if (mVideoSampler != VK_NULL_HANDLE) {
        vkDestroySampler(mDevice, mVideoSampler, nullptr);
        mVideoSampler = VK_NULL_HANDLE;
    }
    if (mYcbcrConversion != VK_NULL_HANDLE) {
        vkDestroySamplerYcbcrConversion(mDevice, mYcbcrConversion, nullptr);
        mYcbcrConversion = VK_NULL_HANDLE;
    }
    mVideoPipelineCreated = false;
}

bool VulkanRenderer::createVideoPipelineOnce(VkAndroidHardwareBufferFormatPropertiesANDROID& formatProps) {
    if (mVideoPipelineCreated) return true;

    if (mYcbcrConversion != VK_NULL_HANDLE) {
        vkDestroySamplerYcbcrConversion(mDevice, mYcbcrConversion, nullptr);
        mYcbcrConversion = VK_NULL_HANDLE;
    }

    // 1. Create YCbCr Conversion
    VkExternalFormatANDROID externalFormat{};
    externalFormat.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
    externalFormat.externalFormat = formatProps.externalFormat;

    VkSamplerYcbcrConversionCreateInfo ycbcrCreateInfo{};
    ycbcrCreateInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO;
    ycbcrCreateInfo.pNext = &externalFormat;
    ycbcrCreateInfo.format = formatProps.format;
    ycbcrCreateInfo.ycbcrModel = formatProps.suggestedYcbcrModel;
    ycbcrCreateInfo.ycbcrRange = formatProps.suggestedYcbcrRange;
    ycbcrCreateInfo.components = formatProps.samplerYcbcrConversionComponents;
    ycbcrCreateInfo.xChromaOffset = formatProps.suggestedXChromaOffset;
    ycbcrCreateInfo.yChromaOffset = formatProps.suggestedYChromaOffset;
    ycbcrCreateInfo.chromaFilter = VK_FILTER_LINEAR;
    ycbcrCreateInfo.forceExplicitReconstruction = VK_FALSE;

    if (vkCreateSamplerYcbcrConversion(mDevice, &ycbcrCreateInfo, nullptr, &mYcbcrConversion) != VK_SUCCESS) {
        LOGE("Failed to create YCbCr conversion");
        return false;
    }

    // 2. Create Immutable Sampler
    VkSamplerYcbcrConversionInfo ycbcrConversionInfo{};
    ycbcrConversionInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO;
    ycbcrConversionInfo.conversion = mYcbcrConversion;

    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.pNext = &ycbcrConversionInfo;
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

    if (vkCreateSampler(mDevice, &samplerInfo, nullptr, &mVideoSampler) != VK_SUCCESS) {
        LOGE("Failed to create video sampler");
        return false;
    }

    // 3. Create Descriptor Set Layout
    VkDescriptorSetLayoutBinding samplerLayoutBinding{};
    samplerLayoutBinding.binding = 0;
    samplerLayoutBinding.descriptorCount = 1;
    samplerLayoutBinding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    samplerLayoutBinding.pImmutableSamplers = &mVideoSampler;
    samplerLayoutBinding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 1;
    layoutInfo.pBindings = &samplerLayoutBinding;

    if (vkCreateDescriptorSetLayout(mDevice, &layoutInfo, nullptr, &mVideoDescriptorSetLayout) != VK_SUCCESS) {
        LOGE("Failed to create descriptor set layout");
        return false;
    }

    // 4. Create Descriptor Pool and Set
    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSize.descriptorCount = 1;

    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    poolInfo.maxSets = 1;

    if (vkCreateDescriptorPool(mDevice, &poolInfo, nullptr, &mVideoDescriptorPool) != VK_SUCCESS) {
        LOGE("Failed to create descriptor pool");
        return false;
    }

    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = mVideoDescriptorPool;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &mVideoDescriptorSetLayout;

    if (vkAllocateDescriptorSets(mDevice, &allocInfo, &mVideoDescriptorSet) != VK_SUCCESS) {
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
    pipelineLayoutInfo.pSetLayouts = &mVideoDescriptorSetLayout;
    pipelineLayoutInfo.pushConstantRangeCount = 1;
    pipelineLayoutInfo.pPushConstantRanges = &pushConstantRange;

    if (vkCreatePipelineLayout(mDevice, &pipelineLayoutInfo, nullptr, &mVideoPipelineLayout) != VK_SUCCESS) {
        LOGE("Failed to create pipeline layout");
        return false;
    }

    // 6. Create Graphics Pipeline
    auto vertShaderCode = readAsset(mAssetManager, "shaders/video.vert.spv");
    auto fragShaderCode = readAsset(mAssetManager, "shaders/video.frag.spv");

    VkShaderModule vertShaderModule = createShaderModule(mDevice, vertShaderCode);
    VkShaderModule fragShaderModule = createShaderModule(mDevice, fragShaderCode);

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
    pipelineInfo.layout = mVideoPipelineLayout;
    pipelineInfo.renderPass = mOffscreenRenderPass;
    pipelineInfo.subpass = 0;

    if (vkCreateGraphicsPipelines(mDevice, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &mVideoPipeline) != VK_SUCCESS) {
        LOGE("Failed to create graphics pipeline");
        return false;
    }

    vkDestroyShaderModule(mDevice, fragShaderModule, nullptr);
    vkDestroyShaderModule(mDevice, vertShaderModule, nullptr);

    mVideoPipelineCreated = true;
    LOGI("Video pipeline created successfully");
    return true;
}


void VulkanRenderer::setLayerTransform(float matrix[16], float opacity) {
    for(int i=0; i<16; i++) mLayerTransform.matrix[i] = matrix[i];
    mLayerTransform.opacity = opacity;
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

    if (vkCreateRenderPass(mDevice, &renderPassInfo, nullptr, &mOffscreenRenderPass) != VK_SUCCESS) return false;

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

bool VulkanRenderer::renderExportFrame() {
    if (!mInitialized || mDevice == VK_NULL_HANDLE || mDevice == (VkDevice)1) return false;

    AHardwareBuffer* newBuffer = mStagedBuffer.exchange(nullptr);
    int64_t newGeneration = mStagedGeneration.load();
    int newCropWidth = mStagedCropWidth.load();
    int newCropHeight = mStagedCropHeight.load();

    if (newBuffer) {
        if (mCurrentRenderingBuffer) {
            cleanupVideoImage();
            AHardwareBuffer_release(mCurrentRenderingBuffer);
            if (mCurrentRenderingGeneration >= 0) {
                int64_t currentLast = mLastConsumedGeneration.load();
                while (mCurrentRenderingGeneration > currentLast) {
                    if (mLastConsumedGeneration.compare_exchange_weak(currentLast, mCurrentRenderingGeneration)) {
                        break;
                    }
                }
            }
        }

        mCurrentRenderingBuffer = newBuffer;
        mCurrentRenderingGeneration = newGeneration;
        mCurrentCropWidth = newCropWidth;
        mCurrentCropHeight = newCropHeight;
        
        createHardwareBufferImage(mCurrentRenderingBuffer);
    }

    VkCommandBuffer commandBuffer = mCommandBuffers[0];
    vkResetCommandBuffer(commandBuffer, 0);

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;

    if (vkBeginCommandBuffer(commandBuffer, &beginInfo) != VK_SUCCESS) {
        LOGE("Failed to begin recording command buffer for export!");
        return false;
    }

    VkRenderPassBeginInfo renderPassInfo{};
    renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    renderPassInfo.renderPass = mOffscreenRenderPass;
    renderPassInfo.framebuffer = mOffscreenFramebuffer;
    renderPassInfo.renderArea.offset = {0, 0};
    renderPassInfo.renderArea.extent = {(uint32_t)mCompWidth, (uint32_t)mCompHeight};

    VkClearValue clearColor = {{{0.0f, 0.0f, 0.0f, 1.0f}}};
    renderPassInfo.clearValueCount = 1;
    renderPassInfo.pClearValues = &clearColor;

    if (mVideoImage != VK_NULL_HANDLE) {
        VkImageMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = mVideoImage;
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

    if (mVideoPipelineCreated && mVideoDescriptorSet != VK_NULL_HANDLE) {
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, mVideoPipeline);
        
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

        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, mVideoPipelineLayout, 0, 1, &mVideoDescriptorSet, 0, nullptr);
        vkCmdPushConstants(commandBuffer, mVideoPipelineLayout, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(mLayerTransform), &mLayerTransform);
        vkCmdDraw(commandBuffer, 6, 1, 0, 0);
    }

    vkCmdEndRenderPass(commandBuffer);

    if (vkEndCommandBuffer(commandBuffer) != VK_SUCCESS) {
        LOGE("Failed to record command buffer for export!");
        return false;
    }

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer;

    vkResetFences(mDevice, 1, &mInFlightFence);

    if (vkQueueSubmit(mGraphicsQueue, 1, &submitInfo, mInFlightFence) != VK_SUCCESS) {
        LOGE("Failed to submit draw command buffer for export!");
        return false;
    }

    vkWaitForFences(mDevice, 1, &mInFlightFence, VK_TRUE, UINT64_MAX);

    return true;
}

bool VulkanRenderer::readbackOffscreenPixels(void* outputBuffer, uint32_t bufferSize) {
    if (!mInitialized || mDevice == VK_NULL_HANDLE || mDevice == (VkDevice)1) return false;

    VkBuffer stagingBuffer;
    VkDeviceMemory stagingBufferMemory;

    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = bufferSize;
    bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    if (vkCreateBuffer(mDevice, &bufferInfo, nullptr, &stagingBuffer) != VK_SUCCESS) return false;

    VkMemoryRequirements memRequirements;
    vkGetBufferMemoryRequirements(mDevice, stagingBuffer, &memRequirements);

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memRequirements.size;

    VkPhysicalDeviceMemoryProperties memProperties;
    vkGetPhysicalDeviceMemoryProperties(mPhysicalDevice, &memProperties);

    uint32_t memoryTypeIndex = 0;
    bool found = false;
    for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
        if ((memRequirements.memoryTypeBits & (1 << i)) && 
            (memProperties.memoryTypes[i].propertyFlags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) == 
            (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
            memoryTypeIndex = i;
            found = true;
            break;
        }
    }

    if (!found) {
        vkDestroyBuffer(mDevice, stagingBuffer, nullptr);
        return false;
    }

    allocInfo.memoryTypeIndex = memoryTypeIndex;
    if (vkAllocateMemory(mDevice, &allocInfo, nullptr, &stagingBufferMemory) != VK_SUCCESS) {
        vkDestroyBuffer(mDevice, stagingBuffer, nullptr);
        return false;
    }
    vkBindBufferMemory(mDevice, stagingBuffer, stagingBufferMemory, 0);

    VkCommandBuffer commandBuffer = mCommandBuffers[0];
    vkResetCommandBuffer(commandBuffer, 0);

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    vkBeginCommandBuffer(commandBuffer, &beginInfo);

    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = mOffscreenImage;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.baseMipLevel = 0;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.baseArrayLayer = 0;
    barrier.subresourceRange.layerCount = 1;
    barrier.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;

    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);

    VkBufferImageCopy region{};
    region.bufferOffset = 0;
    region.bufferRowLength = 0;
    region.bufferImageHeight = 0;
    region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.imageSubresource.mipLevel = 0;
    region.imageSubresource.baseArrayLayer = 0;
    region.imageSubresource.layerCount = 1;
    region.imageOffset = {0, 0, 0};
    region.imageExtent = {(uint32_t)mCompWidth, (uint32_t)mCompHeight, 1};

    vkCmdCopyImageToBuffer(commandBuffer, mOffscreenImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, stagingBuffer, 1, &region);

    if (vkEndCommandBuffer(commandBuffer) != VK_SUCCESS) {
        vkDestroyBuffer(mDevice, stagingBuffer, nullptr);
        vkFreeMemory(mDevice, stagingBufferMemory, nullptr);
        return false;
    }

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer;

    vkResetFences(mDevice, 1, &mInFlightFence);
    if (vkQueueSubmit(mGraphicsQueue, 1, &submitInfo, mInFlightFence) != VK_SUCCESS) {
        vkDestroyBuffer(mDevice, stagingBuffer, nullptr);
        vkFreeMemory(mDevice, stagingBufferMemory, nullptr);
        return false;
    }
    vkWaitForFences(mDevice, 1, &mInFlightFence, VK_TRUE, UINT64_MAX);

    void* data;
    if (vkMapMemory(mDevice, stagingBufferMemory, 0, bufferSize, 0, &data) == VK_SUCCESS) {
        memcpy(outputBuffer, data, bufferSize);
        vkUnmapMemory(mDevice, stagingBufferMemory);
    } else {
        vkDestroyBuffer(mDevice, stagingBuffer, nullptr);
        vkFreeMemory(mDevice, stagingBufferMemory, nullptr);
        return false;
    }

    vkDestroyBuffer(mDevice, stagingBuffer, nullptr);
    vkFreeMemory(mDevice, stagingBufferMemory, nullptr);

    return true;
}
