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
#include <set>
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
    int64_t upsertText(const std::string& font, const std::string& utf8,bool ligatures=true);
    int64_t upsertStyledText(const std::string& font,const fluxx::text::StyledInput& input,bool ligatures);
      int64_t upsertPreparedText(const uint8_t* bytes,size_t length);
      void clearTextCharacterBindings() { if(mText) mText->clearCharacterBindings(); }
      void bindTextCharacters(int64_t handle,const std::vector<int64_t>& catalogues,const std::vector<uint32_t>& codepoints,
          const std::vector<uint32_t>& fonts,const std::string& source,const fluxx::text::GlyphModifier* modifiers,
          const int32_t* codes,std::shared_ptr<void> pin,size_t modifierBytes);
    bool prepareText(const int64_t* handles, size_t count, bool proofOnly);
    bool reusePreparedText() {
        return !mPreparedTextHandles.empty() && prepareText(mPreparedTextHandles.data(),mPreparedTextHandles.size(),mPreparedProofOnly);
    }
    void configureText(unsigned pages) { mTextMaxPages=pages; }
    // SDF atlas tier per layout handle (ppem from text/sdf_tiers.h). Returns true when any tier changed.
    bool setTextTiers(const int64_t* handles,const int32_t* ppems,size_t count,bool synchronous);
    size_t awaitTextTiers(int timeoutMs) { return mText?mText->awaitTiers(timeoutMs):0; }
    // TEXT PERF 11: queue the bundled glyphs of each layout for every tier up to ppem, at idle priority.
    void warmTextTiers(const int64_t* handles,const int32_t* ppems,size_t count) {
        if(!mText) return;
        for(size_t i=0;i<count;++i) { const auto found=mTextLayouts.find(handles[i]); if(found!=mTextLayouts.end()) mText->warmTier(*found->second,static_cast<unsigned>(ppems[i])); }
    }
    // TEXT FIX 9c: imported-font tier glyphs, rasterised by the isolated font worker on the app's behalf.
    std::vector<uint8_t> takeTextTierRequests();
    size_t supplyTextTierGlyphs(const uint8_t* bytes,size_t length);
    bool setTextLayer(int64_t handle, const float* matrix, float size, int alignment, uint32_t argb, float opacity, float tracking=0, float leading=0, float baselineShift=0,
                      uint32_t stroke=0xff000000, float strokeWidth=0, int paintOrder=0, float softness=0,
                      const fluxx::text::GlyphModifier* modifiers=nullptr,size_t modifierBytes=0);
    void releaseText(int64_t handle);
    std::vector<double> textMetrics(int64_t handle) const { return fluxx::text::layoutMetrics(*mTextLayouts.at(handle)); }
    bool validationEnabled() const { return mValidationEnabled; }
    uint64_t validationErrors() const { return mValidationErrors.load(); }
    std::string textStatsJson() const;
    void textFrameCounters(int64_t handle, int64_t* out) {
        const auto gpu=mText->stats(); const auto font=mText->fontStats();
        out[0]=gpu.meshBuildNs; out[1]=font.rasterizations; out[2]=gpu.uploads;
        out[3]=font.shapes; out[4]=mTextLayouts.at(handle)->glyphs.size(); out[5]=gpu.generation;
    }
    // TEXT PERF 10: frame stage profile (ns accumulated since the last read; GPU from timestamp queries). Diagnostic
    // only: reading it never changes rendering. Layout matches FrameStages in RenderBridge.kt.
    // TEXT PERF 11 appends per-layer counters: text layers drawn, native mesh build (ns), re-collect+pack (ns) and
    // count, atlas upload CPU (ns), tier-change calls, prepared-glyph placement lookups (layouts re-resolved).
    enum Stage : size_t { WorkFenceWait, BatchRecord, BatchSubmitWait, PresentFenceWait, Acquire, PresentRecord,
        PresentSubmit, PresentIdleWait, GpuBatch, GpuPresent, Batches, Frames,
        TextLayers, MeshBuild, CollectPack, Collects, AtlasUpload, TierChanges, PlacementLookups, MergedFlushes,
        // MP2.2 (diagnostic only, appended): raster/video layer imports (new AHardwareBuffer in a slot) and their CPU ns
        // (pipeline creation included), slot pipeline / YCbCr conversion creations and ns, slot evictions, raster draws
        // staged, draws whose slot kept its buffer (no import), vkQueueSubmit call ns and fence-wait ns inside submitWork
        // (batches and readback), readback (submit + wait) ns, RGBA->YUV CPU ns, layout transitions recorded. With the
        // probe on (setProbe): distinct AHardwareBuffer ids seen since the probe was enabled, and the hits a per-buffer
        // import cache of 4 / 8 / 16 / 32 / unbounded entries (LRU by id) would have had, out of IdSamples imports.
        Imports, ImportNs, PipelineCreates, PipelineNs, Conversions, SlotEvictions, RasterDraws, SameBuffer,
        QueueSubmitNs, FenceWaitNs, ReadbackNs, YuvNs, Transitions,
        IdsDistinct, Hit4, Hit8, Hit16, Hit32, HitAll, IdSamples, StageCount };
    void frameStages(int64_t* out, size_t count) {
        mStages[IdsDistinct] = static_cast<int64_t>(mSeenIds.size());
        for (size_t i = 0; i < count && i < StageCount; ++i) out[i] = mStages[i];
        std::fill(std::begin(mStages), std::end(mStages), 0);
    }
    // MP2.2: test-only buffer-id probe (off by default; enabling clears the id history). First pipeline creations' ns.
    void setProbe(bool on) { mProbe = on; mSeenIds.clear(); mRecentIds.clear(); }
    // MP2.2 B2: the device's memory types and the readback type chosen (diagnostic text).
    std::string memoryInfo() const { return mMemoryInfo; }
    void pipelineTimes(int64_t* out, size_t count) {
        for (size_t i = 0; i < count; ++i) out[i] = i < mPipelineTimes.size() ? mPipelineTimes[i] : -1;
    }
    bool proofSurface(ANativeWindow* window, int width, int height);
    void proofRepack();
    bool proofPixels(uint8_t* output, size_t capacity);
    bool beginFrame(int width, int height, int presentationWidth = 0, int presentationHeight = 0);
    bool setFrameLayer(int64_t id, AHardwareBuffer* buffer, const float* matrix, float opacity);
    bool finishFrame();
    // Kotlin's flush (four entries at most, unchanged). A text-only batch holds no Kotlin-owned resource after
    // setTextLayer returns, so TEXT PERF 11 merges it into the next real submit; any batch with a raster entry, and
    // finishFrame, submits everything pending and waits for its fence as before.
    bool flushBatch();
    // Debug parity switch: false restores one submit per flush (TEXT PERF 10 behaviour) for pixel comparison.
    void setMergeTextBatches(bool merge) { mMergeText=merge; }
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
    std::vector<DrawEntry> mDeferred; // TEXT PERF 11: text-only batches flushed by Kotlin, submitted with the next real batch.
    bool mMergeText=true;
    bool submitPending();
    std::unique_ptr<fluxx::text::TextRenderer> mText;
    std::map<int64_t,std::shared_ptr<const fluxx::text::Layout>> mTextLayouts;
    std::vector<std::shared_ptr<const fluxx::text::Layout>> mTierLayouts; // TEXT FIX 9d: reused per frame.
    int64_t mNextTextHandle=1;
    bool mTextPrepared=false;
    std::vector<int64_t> mPreparedTextHandles;
    bool mPreparedProofOnly=false;
    unsigned mTextMaxPages=4;
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
    int64_t mStages[StageCount]{};
    bool mProbe = false;
    std::set<uint64_t> mSeenIds;
    std::vector<uint64_t> mRecentIds; // most recent first, at most 32
    std::vector<int64_t> mPipelineTimes; // ns of this session's first 16 slot pipeline creations
    void probeBuffer(AHardwareBuffer* buffer);
    // MP2.2 B2 state.
    bool mReadbackCoherent = true;
    std::string mMemoryInfo;
    VkQueryPool mTimestamps = VK_NULL_HANDLE; // 0/1: batch begin/end, 2/3: present blit begin/end.
    double mTimestampPeriodNs = 0;
    uint64_t mTimestampMask = 0;
    int64_t gpuSpanNs(uint32_t first);
    bool mPresentTimed = false;
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
