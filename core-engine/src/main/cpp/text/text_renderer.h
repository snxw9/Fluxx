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
struct TextVertex { float x, y, u, v; float colour[4] = {1,1,1,1}; float page=0;
    float stroke[4]={0,0,0,1}; float sdf[4]={0,0,1,0}; };
struct GlyphModifier {
    // Global text-local affine after prepared spacing, followed by the layer matrix.
    float a=1, b=0, c=0, d=1, tx=0, ty=0;
    float offsetX=0,offsetY=0;
    float fill[4]={1,1,1,1},stroke[4]={0,0,0,1};
    float halfStroke=0,softness=0,opacity=1,strokePixels=0,blurPixels=0,reserved[3]={};
};
static_assert(sizeof(GlyphModifier)==96,"Kotlin direct modifier ABI");
struct TextMesh { uint32_t firstIndex=0, indexCount=0; };
struct TextPush { float matrix[16]; float settings[4]; }; // Paint order and layer opacity only.
struct TextGpuStats {
    uint64_t generation=0, uploads=0, uploadBytes=0, draws=0;
    uint64_t arrayRecreations=0, atlasPages=0;
    uint64_t images=0, views=0, samplers=0, buffers=0, memories=0, bytes=0;
    uint64_t pools=0, sets=0, layouts=0, pipelines=0, pipelineLayouts=0;
    uint64_t meshBuildNs=0;
    uint64_t atlasUploadCpuNs=0;
};

// Device-owned state. Preparation finishes before any composition render pass.
class TextRenderer {
public:
    TextRenderer(VkPhysicalDevice physical, VkDevice device, VkRenderPass pass,
                 VkPipelineCache cache, AAssetManager* assets, unsigned maxPages);
    ~TextRenderer();
    TextRenderer(const TextRenderer&)=delete;
    std::shared_ptr<const Layout> layout(const std::string& font, const std::string& utf8, bool ligatures=true);
    std::shared_ptr<const Layout> styledLayout(const std::string& font,const StyledInput& input,bool ligatures);
    void collect(const std::shared_ptr<const Layout>& layout);
    // Called before recording draws. Throws a typed capacity error without a partial frame.
    void pack(bool proofOnly);
    void prepareMesh(const std::shared_ptr<const Layout>& layout);
    void clearCharacterBindings();
    void bindCharacters(const std::shared_ptr<const Layout>& layout,const std::vector<std::shared_ptr<const Layout>>& catalogues,
        const std::vector<uint32_t>& codepoints,const std::vector<uint32_t>& fonts,const std::string& source,
        const GlyphModifier* modifiers,const int32_t* codes,std::shared_ptr<void> pin);
    TextMesh mesh(const Layout& layout, float size, int alignment,
                  const GlyphModifier* modifiers=nullptr,
                  float tracking=0, float leading=0, float baselineShift=0,
                  uint32_t fill=0xffffffff,uint32_t stroke=0xff000000,float strokeWidth=0,float softness=0);
    void upload(VkCommandBuffer command);
    void draw(VkCommandBuffer command, const TextMesh& mesh, const TextPush& push);
    void begin();
    void forget(const Layout* layout) { preparedLayouts_.erase(layout); }
    void retireBatch();
    void repackRetained();
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
    void destroyAtlas();
    void createPipeline();
    VkPhysicalDevice physical_; VkDevice device_; VkRenderPass pass_; VkPipelineCache cache_;
    std::shared_ptr<FontManager> fonts_;
    std::map<Key,std::shared_ptr<const SdfGlyph>> retained_, required_, previousRequired_;
    struct PagePlacement { Placement placement; unsigned page; };
    std::map<Key,PagePlacement> placements_;
    PagedAtlas packed_{};
    unsigned maxPages_, allocatedPages_=0;
    std::vector<TextVertex> vertices_; std::vector<uint32_t> indices_;
    struct PreparedGlyph { const SdfGlyph* sdf=nullptr; float u=0,v=0; unsigned page=0; };
    struct CharacterBinding {
        struct Character { uint32_t original=0,font=0;std::vector<uint32_t> marks; };
        std::vector<std::shared_ptr<const Layout>> catalogues;
        std::vector<uint32_t> codepoints;
        std::vector<Character> characters;
        const int32_t* codes=nullptr;std::shared_ptr<void> pin;size_t maximumGlyphs=0;
    };
    struct PreparedLayout { std::shared_ptr<const Layout> owner; uint64_t generation=UINT64_MAX; std::vector<PreparedGlyph> glyphs;
        std::map<const GlyphModifier*,CharacterBinding> characters; };
    std::map<const Layout*,PreparedLayout> preparedLayouts_;
    Buffer staging_, vertex_, index_;
    VkImage image_=VK_NULL_HANDLE; VkDeviceMemory memory_=VK_NULL_HANDLE;
    VkDeviceSize atlasAllocation_=0;
    VkImageView view_=VK_NULL_HANDLE; VkSampler sampler_=VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorLayout_=VK_NULL_HANDLE;
    VkDescriptorPool pool_=VK_NULL_HANDLE; VkDescriptorSet descriptor_=VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_=VK_NULL_HANDLE; VkPipeline pipeline_=VK_NULL_HANDLE;
    bool dirty_=false, initialized_=false;
    TextGpuStats stats_{};
};
}
