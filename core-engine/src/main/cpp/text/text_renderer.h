#pragma once
#include "font_manager.h"
#include "atlas_packer.h"
#include <vulkan/vulkan.h>
#include <android/asset_manager.h>
#include <array>
#include <map>
#include <stdexcept>

namespace fluxx::text {
struct TextCapacityError : std::runtime_error { using std::runtime_error::runtime_error; };
struct TextVertex { float x, y, u, v; float colour[4] = {1,1,1,1}; };
struct GlyphModifier {
    // Glyph-local affine transform, followed by the layer matrix.
    float a=1, b=0, c=0, d=1, tx=0, ty=0;
    float rgba[4]={1,1,1,1};
    float opacity=1;
};
struct TextMesh { uint32_t firstIndex=0, indexCount=0; };
struct TextPush { float matrix[16]; float fill[4]; };
struct TextGpuStats {
    uint64_t generation=0, uploads=0, uploadBytes=0, draws=0;
    uint64_t images=0, views=0, samplers=0, buffers=0, memories=0, bytes=0;
    uint64_t pools=0, sets=0, layouts=0, pipelines=0, pipelineLayouts=0;
};

// Device-owned state. Preparation finishes before any composition render pass.
class TextRenderer {
public:
    TextRenderer(VkPhysicalDevice physical, VkDevice device, VkRenderPass pass,
                 VkPipelineCache cache, AAssetManager* assets);
    ~TextRenderer();
    TextRenderer(const TextRenderer&)=delete;
    std::shared_ptr<const Layout> layout(const std::string& font, const std::string& utf8);
    void collect(const std::shared_ptr<const Layout>& layout);
    // Called before recording draws. Throws a typed capacity error without a partial frame.
    void pack();
    TextMesh mesh(const Layout& layout, float size, int alignment,
                  const std::vector<GlyphModifier>* modifiers=nullptr);
    void upload(VkCommandBuffer command);
    void draw(VkCommandBuffer command, const TextMesh& mesh, const TextPush& push);
    void begin();
    TextGpuStats stats() const { return stats_; }
    FontStats fontStats() { return fonts_->stats(); }
private:
    struct Buffer { VkBuffer buffer=VK_NULL_HANDLE; VkDeviceMemory memory=VK_NULL_HANDLE;
        void* mapped=nullptr; VkDeviceSize capacity=0, allocated=0; bool coherent=false; };
    using Key=std::pair<std::string,uint32_t>;
    uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags required, VkMemoryPropertyFlags preferred=0);
    void buffer(Buffer& target, VkDeviceSize bytes, VkBufferUsageFlags usage);
    void release(Buffer& target);
    void flush(const Buffer& target);
    void createAtlas();
    void createPipeline();
    VkPhysicalDevice physical_; VkDevice device_; VkRenderPass pass_; VkPipelineCache cache_;
    std::shared_ptr<FontManager> fonts_;
    std::map<Key,std::shared_ptr<const SdfGlyph>> retained_, required_;
    std::map<Key,Placement> placements_;
    PackedAtlas packed_{};
    std::vector<TextVertex> vertices_; std::vector<uint32_t> indices_;
    Buffer staging_, vertex_, index_;
    VkImage image_=VK_NULL_HANDLE; VkDeviceMemory memory_=VK_NULL_HANDLE;
    VkImageView view_=VK_NULL_HANDLE; VkSampler sampler_=VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorLayout_=VK_NULL_HANDLE;
    VkDescriptorPool pool_=VK_NULL_HANDLE; VkDescriptorSet descriptor_=VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_=VK_NULL_HANDLE; VkPipeline pipeline_=VK_NULL_HANDLE;
    bool dirty_=false, initialized_=false;
    TextGpuStats stats_{};
};
}
