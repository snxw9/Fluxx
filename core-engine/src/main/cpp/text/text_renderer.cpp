#include "text_renderer.h"
#include <chrono>
#include "font_catalog.h"
#include "text_shaders.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <cstddef>
#include <set>
#include "../gpu_ledger.h"

namespace fluxx::text {
namespace {
void check(VkResult result, const char* operation) {
    if (result!=VK_SUCCESS) throw std::runtime_error(std::string(operation)+": "+std::to_string(result));
}
template<size_t N> VkShaderModule shader(VkDevice device, const uint32_t (&code)[N]) {
    VkShaderModuleCreateInfo info{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    info.codeSize=sizeof(code); info.pCode=code;
    VkShaderModule module; check(vkCreateShaderModule(device,&info,nullptr,&module),"text shader"); return module;
}
}
TextRenderer::TextRenderer(VkPhysicalDevice physical, VkDevice device, VkRenderPass pass,
    VkPipelineCache cache, AAssetManager* assets, unsigned maxPages):physical_(physical),device_(device),pass_(pass),cache_(cache),
    fonts_(acquireFontCatalog(assets)),maxPages_(maxPages),assets_(assets),cpuBudget_(maxPages<=3?tiers::CpuBudgetLowRam:tiers::CpuBudget) {
    VkFormatProperties format; vkGetPhysicalDeviceFormatProperties(physical_,VK_FORMAT_R8_UNORM,&format);
    const auto required=VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT|VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT|VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
    VkImageFormatProperties image{};
    check(vkGetPhysicalDeviceImageFormatProperties(physical_,VK_FORMAT_R8_UNORM,VK_IMAGE_TYPE_2D,VK_IMAGE_TILING_OPTIMAL,
        VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT,0,&image),"R8 array sampling support");
    VkPhysicalDeviceProperties props; vkGetPhysicalDeviceProperties(physical_,&props);
    if((format.optimalTilingFeatures&required)!=required || image.maxArrayLayers<maxPages ||
        props.limits.maxImageArrayLayers<maxPages || image.maxExtent.width<2048 || image.maxExtent.height<2048)
        throw std::runtime_error("Required R8 texture-array limits unsupported");
}
TextRenderer::~TextRenderer() {
    {
        std::lock_guard<std::mutex> lock(tierMutex_);
        stopWorker_=true; queue_.clear();
    }
    tierWake_.notify_all();
    if(worker_.joinable()) worker_.join();
    // Owner has completed its work fence / device idle before releasing this object.
    release(staging_); release(vertex_); release(index_);
    destroyAtlas();
}
void TextRenderer::destroyAtlas() {
    if(pipeline_) vkDestroyPipeline(device_,pipeline_,nullptr);
    if(pipelineLayout_) vkDestroyPipelineLayout(device_,pipelineLayout_,nullptr);
    if(pool_) vkDestroyDescriptorPool(device_,pool_,nullptr);
    if(descriptorLayout_) vkDestroyDescriptorSetLayout(device_,descriptorLayout_,nullptr);
    if(sampler_) vkDestroySampler(device_,sampler_,nullptr);
    if(view_) vkDestroyImageView(device_,view_,nullptr);
    if(image_) vkDestroyImage(device_,image_,nullptr);
    if(memory_) vkFreeMemory(device_,memory_,nullptr);
    pipeline_=VK_NULL_HANDLE; pipelineLayout_=VK_NULL_HANDLE;
    pool_=VK_NULL_HANDLE; descriptor_=VK_NULL_HANDLE; descriptorLayout_=VK_NULL_HANDLE;
    sampler_=VK_NULL_HANDLE; view_=VK_NULL_HANDLE; image_=VK_NULL_HANDLE; memory_=VK_NULL_HANDLE;
    stats_.images=stats_.views=stats_.samplers=stats_.pools=stats_.sets=stats_.layouts=stats_.pipelines=stats_.pipelineLayouts=0;
    if(atlasAllocation_) { --stats_.memories; stats_.bytes-=atlasAllocation_; }
    atlasAllocation_=0; initialized_=false;
}
uint32_t TextRenderer::memoryType(uint32_t bits, VkMemoryPropertyFlags required, VkMemoryPropertyFlags preferred) {
    VkPhysicalDeviceMemoryProperties props; vkGetPhysicalDeviceMemoryProperties(physical_,&props);
    for(int pass=0;pass<2;++pass) for(uint32_t i=0;i<props.memoryTypeCount;++i)
        if((bits&(1u<<i)) && (props.memoryTypes[i].propertyFlags&required)==required &&
            (pass || (props.memoryTypes[i].propertyFlags&preferred)==preferred)) return i;
    throw std::runtime_error("No compatible text memory type");
}
void TextRenderer::release(Buffer& target) {
    if(target.mapped) vkUnmapMemory(device_,target.memory);
    if(target.buffer) { vkDestroyBuffer(device_,target.buffer,nullptr); --stats_.buffers; }
    if(target.memory) { vkFreeMemory(device_,target.memory,nullptr); --stats_.memories; stats_.bytes-=target.allocated; }
    target={};
}
void TextRenderer::buffer(Buffer& target, VkDeviceSize bytes, VkBufferUsageFlags usage) {
    if(bytes==0 || target.capacity>=bytes) return;
    release(target);
    VkBufferCreateInfo info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO}; info.size=bytes; info.usage=usage;
    info.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    check(vkCreateBuffer(device_,&info,nullptr,&target.buffer),"text buffer"); ++stats_.buffers;
    VkMemoryRequirements requirements; vkGetBufferMemoryRequirements(device_,target.buffer,&requirements);
    const uint32_t type=memoryType(requirements.memoryTypeBits,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    VkPhysicalDeviceMemoryProperties props; vkGetPhysicalDeviceMemoryProperties(physical_,&props);
    target.coherent=(props.memoryTypes[type].propertyFlags&VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)!=0;
    VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; allocation.allocationSize=requirements.size; allocation.memoryTypeIndex=type;
    check(vkAllocateMemory(device_,&allocation,nullptr,&target.memory),"text buffer memory");
    ++stats_.memories; stats_.bytes+=requirements.size; target.allocated=requirements.size;
    check(vkBindBufferMemory(device_,target.buffer,target.memory,0),"text buffer bind");
    check(vkMapMemory(device_,target.memory,0,VK_WHOLE_SIZE,0,&target.mapped),"text buffer map"); target.capacity=bytes;
}
void TextRenderer::flush(const Buffer& target) {
    if(target.coherent || !target.memory) return;
    VkMappedMemoryRange range{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE}; range.memory=target.memory; range.size=VK_WHOLE_SIZE;
    check(vkFlushMappedMemoryRanges(device_,1,&range),"text flush");
}
void TextRenderer::createAtlas() {
    if(image_) return;
    try {
    VkFormatProperties format; vkGetPhysicalDeviceFormatProperties(physical_,VK_FORMAT_R8_UNORM,&format);
    const auto required=VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT|VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT|VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
    if((format.optimalTilingFeatures&required)!=required) throw std::runtime_error("R8 linear-filtered atlas unsupported");
    VkPhysicalDeviceProperties props; vkGetPhysicalDeviceProperties(physical_,&props);
    if(props.limits.maxImageDimension2D<2048) throw TextCapacityError("2048 text atlas unsupported");
    VkImageCreateInfo image{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO}; image.imageType=VK_IMAGE_TYPE_2D;
    image.format=VK_FORMAT_R8_UNORM; image.extent={2048,2048,1}; image.mipLevels=1; image.arrayLayers=allocatedPages_;
    image.samples=VK_SAMPLE_COUNT_1_BIT; image.tiling=VK_IMAGE_TILING_OPTIMAL;
    image.usage=VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT; image.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    check(vkCreateImage(device_,&image,nullptr,&image_),"text atlas"); ++stats_.images;
    VkMemoryRequirements requirements; vkGetImageMemoryRequirements(device_,image_,&requirements);
    VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; allocation.allocationSize=requirements.size;
    allocation.memoryTypeIndex=memoryType(requirements.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    check(vkAllocateMemory(device_,&allocation,nullptr,&memory_),"text atlas memory"); ++stats_.memories; stats_.bytes+=requirements.size;
    atlasAllocation_=requirements.size;
    check(vkBindImageMemory(device_,image_,memory_,0),"text atlas bind");
    VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO}; view.image=image_; view.viewType=VK_IMAGE_VIEW_TYPE_2D_ARRAY;
    view.format=VK_FORMAT_R8_UNORM; view.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,allocatedPages_};
    check(vkCreateImageView(device_,&view,nullptr,&view_),"text atlas view"); ++stats_.views;
    VkSamplerCreateInfo sampler{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO}; sampler.magFilter=VK_FILTER_LINEAR; sampler.minFilter=VK_FILTER_LINEAR;
    sampler.mipmapMode=VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sampler.addressModeU=sampler.addressModeV=sampler.addressModeW=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    check(vkCreateSampler(device_,&sampler,nullptr,&sampler_),"text sampler"); ++stats_.samplers;
    createPipeline();
    } catch(...) { destroyAtlas(); throw; }
}
void TextRenderer::createPipeline() {
    VkDescriptorSetLayoutBinding binding{}; binding.binding=0; binding.descriptorCount=1;
    binding.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; binding.stageFlags=VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo descriptor{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO}; descriptor.bindingCount=1; descriptor.pBindings=&binding;
    check(vkCreateDescriptorSetLayout(device_,&descriptor,nullptr,&descriptorLayout_),"text descriptor layout"); ++stats_.layouts;
    VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,1};
    VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO}; pool.maxSets=1; pool.poolSizeCount=1; pool.pPoolSizes=&size;
    check(vkCreateDescriptorPool(device_,&pool,nullptr,&pool_),"text descriptor pool"); ++stats_.pools;
    VkDescriptorSetAllocateInfo set{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO}; set.descriptorPool=pool_; set.descriptorSetCount=1; set.pSetLayouts=&descriptorLayout_;
    check(vkAllocateDescriptorSets(device_,&set,&descriptor_),"text descriptor"); ++stats_.sets;
    VkDescriptorImageInfo image{sampler_,view_,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}; write.dstSet=descriptor_; write.dstBinding=0;
    write.descriptorCount=1; write.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; write.pImageInfo=&image;
    vkUpdateDescriptorSets(device_,1,&write,0,nullptr);
    VkPushConstantRange push{VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,sizeof(TextPush)};
    VkPipelineLayoutCreateInfo layout{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO}; layout.setLayoutCount=1; layout.pSetLayouts=&descriptorLayout_;
    layout.pushConstantRangeCount=1; layout.pPushConstantRanges=&push;
    check(vkCreatePipelineLayout(device_,&layout,nullptr,&pipelineLayout_),"text pipeline layout"); ++stats_.pipelineLayouts;
    VkShaderModule vert=VK_NULL_HANDLE, frag=VK_NULL_HANDLE;
    try {
        vert=shader(device_,TEXT_VERT); frag=shader(device_,TEXT_FRAG);
        VkPipelineShaderStageCreateInfo stages[2]{};
        for(auto& stage:stages) { stage.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stage.pName="main"; }
        stages[0].stage=VK_SHADER_STAGE_VERTEX_BIT; stages[0].module=vert;
        stages[1].stage=VK_SHADER_STAGE_FRAGMENT_BIT; stages[1].module=frag;
        VkVertexInputBindingDescription bindingDescription{0,sizeof(TextVertex),VK_VERTEX_INPUT_RATE_VERTEX};
        VkVertexInputAttributeDescription attributes[]={{0,0,VK_FORMAT_R32G32_SFLOAT,offsetof(TextVertex,x)},
            {1,0,VK_FORMAT_R32G32_SFLOAT,offsetof(TextVertex,u)},{2,0,VK_FORMAT_R32G32B32A32_SFLOAT,offsetof(TextVertex,colour)},
            {3,0,VK_FORMAT_R32_SFLOAT,offsetof(TextVertex,page)},
            {4,0,VK_FORMAT_R32G32B32A32_SFLOAT,offsetof(TextVertex,stroke)},
            {5,0,VK_FORMAT_R32G32B32A32_SFLOAT,offsetof(TextVertex,sdf)}};
        VkPipelineVertexInputStateCreateInfo input{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
        input.vertexBindingDescriptionCount=1; input.pVertexBindingDescriptions=&bindingDescription;
    input.vertexAttributeDescriptionCount=6; input.pVertexAttributeDescriptions=attributes;
        VkPipelineInputAssemblyStateCreateInfo assembly{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO}; assembly.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VkPipelineViewportStateCreateInfo viewport{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO}; viewport.viewportCount=1; viewport.scissorCount=1;
        VkPipelineRasterizationStateCreateInfo raster{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
        raster.polygonMode=VK_POLYGON_MODE_FILL; raster.cullMode=VK_CULL_MODE_NONE; raster.lineWidth=1;
        VkPipelineMultisampleStateCreateInfo samples{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO}; samples.rasterizationSamples=VK_SAMPLE_COUNT_1_BIT;
        VkPipelineColorBlendAttachmentState blend{}; blend.blendEnable=VK_TRUE;
        blend.srcColorBlendFactor=VK_BLEND_FACTOR_SRC_ALPHA; blend.dstColorBlendFactor=VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        blend.colorBlendOp=VK_BLEND_OP_ADD; blend.srcAlphaBlendFactor=VK_BLEND_FACTOR_ONE; blend.dstAlphaBlendFactor=VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        blend.alphaBlendOp=VK_BLEND_OP_ADD; blend.colorWriteMask=15;
        VkPipelineColorBlendStateCreateInfo blending{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO}; blending.attachmentCount=1; blending.pAttachments=&blend;
        const VkDynamicState dynamicStates[]={VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR};
        VkPipelineDynamicStateCreateInfo dynamic{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO}; dynamic.dynamicStateCount=2; dynamic.pDynamicStates=dynamicStates;
        VkGraphicsPipelineCreateInfo pipeline{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO}; pipeline.stageCount=2; pipeline.pStages=stages;
        pipeline.pVertexInputState=&input; pipeline.pInputAssemblyState=&assembly; pipeline.pViewportState=&viewport;
        pipeline.pRasterizationState=&raster; pipeline.pMultisampleState=&samples; pipeline.pColorBlendState=&blending;
        pipeline.pDynamicState=&dynamic; pipeline.layout=pipelineLayout_; pipeline.renderPass=pass_;
        check(vkCreateGraphicsPipelines(device_,cache_,1,&pipeline,nullptr,&pipeline_),"text pipeline"); ++stats_.pipelines;
    } catch(...) {
        if(vert) vkDestroyShaderModule(device_,vert,nullptr);
        if(frag) vkDestroyShaderModule(device_,frag,nullptr);
        throw;
    }
    vkDestroyShaderModule(device_,vert,nullptr); vkDestroyShaderModule(device_,frag,nullptr);
}
std::shared_ptr<const Layout> TextRenderer::layout(const std::string& font, const std::string& utf8, bool ligatures) {
    auto result=fonts_->shape(font,utf8,ligatures);
    std::set<uint32_t> unique;
    for(const auto& glyph:result->glyphs) if(unique.insert(glyph.id).second) fonts_->sdf(result->fontId,glyph.id);
    return result;
}
void TextRenderer::begin() {
    previousRequired_=std::move(required_); required_.clear(); vertices_.clear(); indices_.clear();
    collected_.clear(); ++frame_; collectedRevision_=tierRevision_.load();
}
void TextRenderer::retireBatch() { vertices_.clear(); indices_.clear(); }
std::shared_ptr<const Layout> TextRenderer::styledLayout(const std::string& font,const StyledInput& input,bool ligatures) {
    auto result=fonts_->shapeStyled(font,input.text,input.runs,input.uppercase,ligatures);
    std::set<Key> unique;
    for(size_t i=0;i<result->glyphs.size();++i) {
        Key key{result->glyphFont(i),result->glyphs[i].id,tiers::BasePpem};
        if(unique.insert(key).second) fonts_->sdf(std::get<0>(key),std::get<1>(key));
    }
    return result;
}
std::shared_ptr<FontManager> TextRenderer::tierFonts() {
    if(!tierFonts_) tierFonts_=acquireTierCatalog(assets_,cpuBudget_);
    return tierFonts_;
}
bool TextRenderer::setTier(const Layout* layout,unsigned ppem) {
    if(!tiers::valid(ppem)) throw std::invalid_argument("Invalid SDF atlas tier");
    auto found=wantedTier_.find(layout);
    const unsigned current=found==wantedTier_.end()?tiers::BasePpem:found->second;
    if(current==ppem) return false;
    wantedTier_[layout]=ppem; tierCap_.erase(layout); ++tierRevision_;
    return true;
}
unsigned TextRenderer::tierFor(const Layout* layout) const {
    const auto wanted=wantedTier_.find(layout);
    unsigned tier=wanted==wantedTier_.end()?tiers::BasePpem:wanted->second;
    const auto cap=tierCap_.find(layout);
    if(cap!=tierCap_.end()) tier=std::min(tier,cap->second);
    return tier;
}
bool TextRenderer::tierReady(const Layout& layout,unsigned ppem) {
    std::lock_guard<std::mutex> lock(tierMutex_);
    for(size_t i=0;i<layout.glyphs.size();++i) {
        const Key key{layout.glyphFont(i),layout.glyphs[i].id,ppem};
        if(retained_.count(key)) continue;
        const auto found=ready_.find(key);
        if(found==ready_.end()) return false;
        // Touched this frame: the CPU cache keeps it until the atlas holds it.
        readyUse_[key]=frame_;
    }
    return true;
}
void TextRenderer::ensureTier(const Layout& layout,unsigned ppem,bool synchronous) {
    std::set<Key> missing;
    {
        std::lock_guard<std::mutex> lock(tierMutex_);
        for(size_t i=0;i<layout.glyphs.size();++i) {
            Key key{layout.glyphFont(i),layout.glyphs[i].id,ppem};
            // External (imported) keys are rasterised by the isolated worker, never here.
            if(!external(std::get<0>(key)) && !retained_.count(key) && !ready_.count(key) && !failed_.count(key)) missing.insert(std::move(key));
        }
        if(!synchronous) {
            for(const auto& key:missing) if(queued_.insert(key).second) queue_.push_back(key);
            if(!missing.empty() && !worker_.joinable()) worker_=std::thread([this] { tierWorker(); });
        }
    }
    if(!synchronous) { tierWake_.notify_one(); return; }
    // Export: generate inline so every frame is deterministic at the export tier.
    for(const auto& key:missing) {
        std::shared_ptr<const SdfGlyph> glyph;
        try { glyph=tierFonts()->sdf(std::get<0>(key),std::get<1>(key),true,ppem); }
        catch(const std::exception&) { std::lock_guard<std::mutex> lock(tierMutex_); failed_.insert(key); continue; }
        std::lock_guard<std::mutex> lock(tierMutex_);
        readyBytes_+=glyph->pixels.size(); ready_[key]=std::move(glyph); readyUse_[key]=frame_;
    }
    std::lock_guard<std::mutex> lock(tierMutex_);
    trimReady();
}
void TextRenderer::tierWorker() {
    std::unique_lock<std::mutex> lock(tierMutex_);
    while(true) {
        tierWake_.wait(lock,[this] { return stopWorker_ || !queue_.empty(); });
        if(stopWorker_) return;
        const Key key=queue_.front(); queue_.pop_front(); busy_=true;
        lock.unlock();
        std::shared_ptr<const SdfGlyph> glyph;
        try { glyph=tierFonts()->sdf(std::get<0>(key),std::get<1>(key),true,std::get<2>(key)); } catch(const std::exception&) {}
        lock.lock();
        queued_.erase(key); busy_=false;
        if(glyph) { readyBytes_+=glyph->pixels.size(); ready_[key]=std::move(glyph); readyUse_[key]=frame_; trimReady(); }
        else failed_.insert(key);
        // A finished glyph may let a layout move up a tier: the next preparation re-collects.
        if(queue_.empty()) ++tierRevision_;
        tierIdle_.notify_all();
    }
}
void TextRenderer::trimReady() {
    // Caller holds tierMutex_. Glyphs also referenced by the atlas (use_count > 1) are never evicted here.
    if(readyBytes_<=cpuBudget_) return;
    std::vector<Key> order;
    const uint64_t now=frame_;
    for(const auto& entry:ready_) if(entry.second.use_count()==1 && readyUse_[entry.first]!=now) order.push_back(entry.first);
    std::sort(order.begin(),order.end(),[this](const Key& a,const Key& b) {
        return tiers::evictBefore({std::get<2>(a),readyUse_[a]},{std::get<2>(b),readyUse_[b]}); });
    for(const auto& key:order) {
        if(readyBytes_<=cpuBudget_) break;
        readyBytes_-=ready_[key]->pixels.size(); ready_.erase(key); readyUse_.erase(key); ++stats_.tierEvictions;
        if(external(std::get<0>(key))) externalSettled_.clear();
    }
}
size_t TextRenderer::awaitTiers(int timeoutMs) {
    std::unique_lock<std::mutex> lock(tierMutex_);
    if(timeoutMs>0) tierIdle_.wait_for(lock,std::chrono::milliseconds(timeoutMs),[this] { return queue_.empty() && !busy_ && externalInFlight_.empty(); });
    return queue_.size()+(busy_?1:0)+externalInFlight_.size();
}
std::vector<TierKey> TextRenderer::takeExternalRequests(const std::vector<std::shared_ptr<const Layout>>& layouts,size_t max) {
    // Steady state allocates nothing (TEXT FIX 9d): a layout whose external keys are all settled at its tier is skipped
    // until an external glyph is evicted or deferred; keys are only built for glyphs of external fonts.
    std::vector<TierKey> requests;
    std::lock_guard<std::mutex> lock(tierMutex_);
    for(const auto& layout:layouts) {
        const unsigned tier=tierFor(layout.get());
        if(tier==tiers::BasePpem) continue;
        const auto settled=externalSettled_.find(layout.get());
        if(settled!=externalSettled_.end() && settled->second==tier) continue;
        bool complete=true;
        for(size_t i=0;i<layout->glyphs.size();++i) {
            const auto& font=layout->glyphFont(i);
            if(!external(font)) continue;
            Key key{font,layout->glyphs[i].id,tier};
            if(retained_.count(key) || ready_.count(key) || failed_.count(key)) continue;
            complete=false;
            if(externalInFlight_.count(key) || requests.size()>=max) continue;
            externalInFlight_.insert(key);requests.push_back({font,layout->glyphs[i].id,tier});
        }
        if(complete) externalSettled_[layout.get()]=tier; else externalSettled_.erase(layout.get());
    }
    stats_.tierExternalRequested+=requests.size();
    return requests;
}
size_t TextRenderer::supplyTierGlyphs(const TierReply& reply) {
    size_t accepted=0;
    {
        std::lock_guard<std::mutex> lock(tierMutex_);
        for(const auto& glyph:reply.glyphs) {
            const Key key{glyph->fontId,glyph->glyphId,glyph->ppem};
            // Only keys this renderer asked for: an isolated-worker reply can never inject other atlas entries.
            if(!externalInFlight_.erase(key)) continue;
            readyBytes_+=glyph->pixels.size();ready_[key]=glyph;readyUse_[key]=frame_;++accepted;
        }
        for(const auto& k:reply.failed) { const Key key{k.fontId,k.glyphId,k.ppem};if(externalInFlight_.erase(key)) { failed_.insert(key);++stats_.tierExternalFailed; } }
        for(const auto& k:reply.deferred) externalInFlight_.erase(Key{k.fontId,k.glyphId,k.ppem}); // Asked for again next frame.
        if(!reply.deferred.empty()) externalSettled_.clear();
        stats_.tierExternalSupplied+=accepted;
        trimReady();
    }
    ++tierRevision_; // The next preparation re-collects and may move layouts up a tier.
    tierIdle_.notify_all();
    return accepted;
}
void TextRenderer::collect(const std::shared_ptr<const Layout>& layout) {
    collected_.push_back(layout);
    collectOne(layout);
}
void TextRenderer::collectOne(const std::shared_ptr<const Layout>& layout) {
    // TEXT FIX 9c: imported fonts (and per-character catalogues) use the same per-layout tier as bundled text. Their base
    // bitmaps arrive in the prepared packet; sharper tiers come from the tier thread (bundled ids) or the isolated worker
    // (external "user.*" ids, see takeExternalRequests). Until a tier is fully ready the lower tier stays in use.
    const unsigned wanted=tierFor(layout.get());
    unsigned resolved=tiers::BasePpem;
    if(wanted>tiers::BasePpem) {
        if(synchronousTiers_) {
            ensureTier(*layout,wanted,true);
            resolved=tiers::resolve(wanted,[&](unsigned t) { return tierReady(*layout,t); });
        } else {
            // Preview: the sharpest tier whose glyphs are all ready; the wanted tier is generated off-thread.
            resolved=tiers::resolve(wanted,[&](unsigned t) { return tierReady(*layout,t); });
            if(resolved<wanted) ensureTier(*layout,wanted,false);
        }
    }
    resolvedTier_[layout.get()]=resolved;
    std::unique_lock<std::mutex> lock(tierMutex_,std::defer_lock);
    if(resolved!=tiers::BasePpem) lock.lock();
    for(size_t i=0;i<layout->glyphs.size();++i) {
        const auto& glyph=layout->glyphs[i];
        Key key{layout->glyphFont(i),glyph.id,resolved};
        lastUse_[key]=frame_;
        if(required_.count(key)) continue;
        auto found=retained_.find(key);
        auto previous=previousRequired_.find(key);
        if(found!=retained_.end()) required_[key]=found->second;
        else if(previous!=previousRequired_.end()) required_[key]=previous->second;
        else if(resolved!=tiers::BasePpem) { required_[key]=ready_.at(key); readyUse_[key]=frame_; }
        else required_[key]=!layout->externalSdfs.empty()?layout->externalSdfs.at(i):fonts_->sdf(std::get<0>(key),std::get<1>(key));
    }
}
bool TextRenderer::degradeOnce() {
    // Over the cap: the layouts on the highest tier step down one tier (until the next tier request).
    unsigned highest=tiers::BasePpem;
    for(const auto& layout:collected_) highest=std::max(highest,resolvedTier_[layout.get()]);
    if(highest==tiers::BasePpem) return false;
    for(const auto& layout:collected_) if(resolvedTier_[layout.get()]==highest) tierCap_[layout.get()]=tiers::lower(highest);
    ++stats_.tierDegrades;
    return true;
}
void TextRenderer::pack(bool proofOnly) {
    bool misses=false; for(const auto& entry:required_) if(!retained_.count(entry.first)) { misses=true; break; }
    if(!misses && (!proofOnly || allocatedPages_<=1)) return;
    std::map<Key,std::shared_ptr<const SdfGlyph>> candidates=retained_;
    candidates.insert(required_.begin(),required_.end());
    const unsigned limit=proofOnly?1:maxPages_;
    auto packEntries=[](const auto& entries,unsigned pages) { std::vector<std::shared_ptr<const SdfGlyph>> glyphs;
        for(const auto& entry:entries) glyphs.push_back(entry.second); return packGlyphPages(std::move(glyphs),pages); };
    const unsigned resident=proofOnly?1:std::max(1u,allocatedPages_);
    auto packed=packEntries(candidates,resident);
    if(packed.rejected) {
        // Evict glyphs this frame does not need: highest tier first, then least recently used.
        std::vector<Key> evictable;
        for(const auto& entry:retained_) if(!required_.count(entry.first)) evictable.push_back(entry.first);
        std::sort(evictable.begin(),evictable.end(),[this](const Key& a,const Key& b) {
            return tiers::evictBefore({std::get<2>(a),lastUse_[a]},{std::get<2>(b),lastUse_[b]}); });
        const size_t step=std::max<size_t>(1,evictable.size()/4);
        for(size_t i=0;i<evictable.size() && packed.rejected;) {
            for(const size_t end=std::min(i+step,evictable.size());i<end;++i) { candidates.erase(evictable[i]); lastUse_.erase(evictable[i]); ++stats_.tierEvictions; }
            packed=packEntries(candidates,resident);
        }
    }
    if(packed.rejected && !proofOnly) packed=packEntries(candidates,limit);
    // Still over the cap: degrade the highest-tier layouts to a lower tier instead of failing.
    while(packed.rejected && !proofOnly && degradeOnce()) {
        const auto layouts=collected_;
        required_.clear(); collected_.clear();
        for(const auto& layout:layouts) collect(layout);
        candidates=required_;
        packed=packEntries(candidates,limit);
    }
    if(packed.rejected) throw TextCapacityError("Required glyph set exceeds the atlas page budget ("+std::to_string(limit)+" pages)");
    const unsigned count=std::max(1u,static_cast<unsigned>(packed.pages.size()));
    if(count>allocatedPages_) {
        check(vkDeviceWaitIdle(device_),"text array growth idle");
        destroyAtlas(); allocatedPages_=count; createAtlas(); ++stats_.arrayRecreations; stats_.atlasPages=count;
    } else if(!image_) { allocatedPages_=std::max(allocatedPages_,count); createAtlas(); stats_.atlasPages=allocatedPages_; }
    retained_=std::move(candidates); packed_=std::move(packed); placements_.clear();
    { std::lock_guard<std::mutex> settledLock(tierMutex_); externalSettled_.clear(); } // Repacked atlas: rescan external keys.
    for(unsigned page=0;page<packed_.pages.size();++page) for(const auto& p:packed_.pages[page].placements)
        placements_.emplace(Key{p.glyph->fontId,p.glyph->glyphId,p.glyph->ppem},PagePlacement{p,page});
    dirty_=true; ++stats_.generation;
    {
        std::lock_guard<std::mutex> lock(tierMutex_);
        stats_.tierGlyphs=ready_.size(); stats_.tierBytes=readyBytes_; stats_.tierPending=queue_.size()+(busy_?1:0)+externalInFlight_.size();
    }
}
void TextRenderer::repackRetained() {
    std::vector<std::shared_ptr<const SdfGlyph>> glyphs;
    for(const auto& entry:retained_) glyphs.push_back(entry.second);
    auto packed=packGlyphPages(std::move(glyphs),std::max(1u,allocatedPages_));
    if(packed.rejected) throw TextCapacityError("Retained atlas repack failed");
    packed_=std::move(packed); placements_.clear();
    for(unsigned page=0;page<packed_.pages.size();++page) for(const auto& p:packed_.pages[page].placements)
        placements_.emplace(Key{p.glyph->fontId,p.glyph->glyphId,p.glyph->ppem},PagePlacement{p,page});
    dirty_=true; ++stats_.generation;
}
void TextRenderer::prepareMesh(const std::shared_ptr<const Layout>& layout) {
    auto& prepared=preparedLayouts_[layout.get()];
    prepared.owner=layout;
    size_t maximumGlyphs=layout->glyphs.size();
    for(const auto& entry:prepared.characters) maximumGlyphs=std::max(maximumGlyphs,entry.second.maximumGlyphs);
    vertices_.reserve(std::max(vertices_.capacity(),maximumGlyphs*16));
    indices_.reserve(std::max(indices_.capacity(),maximumGlyphs*24));
    buffer(vertex_,vertices_.capacity()*sizeof(TextVertex),VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
    buffer(index_,indices_.capacity()*sizeof(uint32_t),VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
    const unsigned tier=resolvedTier(layout.get());
    if (prepared.generation==stats_.generation && prepared.tier==tier) return;
    prepared.glyphs.resize(layout->glyphs.size());
    for(size_t i=0;i<layout->glyphs.size();++i) {
        const Key key{layout->glyphFont(i),layout->glyphs[i].id,tier};
        const auto found=placements_.find(key);
        auto& out=prepared.glyphs[i]; out={};
        if(found!=placements_.end()) {
            const auto& p=found->second;
            out={p.placement.glyph.get(),p.placement.x/2048.0f,p.placement.y/2048.0f,p.page};
        } else if(required_.at(key)->width) throw std::runtime_error("Missing prepared glyph");
    }
    prepared.generation=stats_.generation; prepared.tier=tier;
    vertices_.reserve(std::max(vertices_.capacity(),layout->glyphs.size()*16));
    indices_.reserve(std::max(indices_.capacity(),layout->glyphs.size()*24));
    // GPU buffers are also provisioned during preparation, never by animated mesh evaluation.
    buffer(vertex_,vertices_.capacity()*sizeof(TextVertex),VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
    buffer(index_,indices_.capacity()*sizeof(uint32_t),VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
}
void TextRenderer::clearCharacterBindings() {
    for(auto& entry:preparedLayouts_) entry.second.characters.clear();
}
void TextRenderer::bindCharacters(const std::shared_ptr<const Layout>& layout,
    const std::vector<std::shared_ptr<const Layout>>& catalogues,const std::vector<uint32_t>& codepoints,
    const std::vector<uint32_t>& fonts,const std::string& source,const GlyphModifier* modifiers,const int32_t* codes,std::shared_ptr<void> pin) {
    if(!modifiers || !codes || fonts.size()!=layout->characters.size() || catalogues.empty() || codepoints.empty() || codepoints.size()>4096 ||
       catalogues.size()*codepoints.size()>16384 || !std::is_sorted(codepoints.begin(),codepoints.end()) ||
       std::adjacent_find(codepoints.begin(),codepoints.end())!=codepoints.end()) throw std::invalid_argument("Invalid character catalogue binding");
    for(const auto& catalogue:catalogues) if(!catalogue->styled || catalogue->glyphs.size()!=codepoints.size() || catalogue->upem!=1000)
        throw std::invalid_argument("Character catalogue must contain one nominal glyph per codepoint");
    CharacterBinding binding;binding.catalogues=catalogues;binding.codepoints=codepoints;binding.codes=codes;binding.pin=std::move(pin);
    for(size_t i=0;i<layout->characters.size();++i) {
        const auto& member=layout->characters[i];
        if(fonts[i]>=catalogues.size() || member.byteEnd>source.size()) throw std::invalid_argument("Invalid source character binding");
        CharacterBinding::Character c;c.font=fonts[i];size_t at=member.byteStart;
        while(at<member.byteEnd) {
            uint32_t cp=static_cast<uint8_t>(source[at++]);unsigned extra=0;
            if(cp>=0xf0) { cp&=7;extra=3; } else if(cp>=0xe0) { cp&=15;extra=2; } else if(cp>=0xc0) { cp&=31;extra=1; }
            if(extra>member.byteEnd-at) throw std::invalid_argument("Truncated source character binding");
            while(extra--) cp=(cp<<6)|(static_cast<uint8_t>(source[at++])&63);
            if(!std::binary_search(codepoints.begin(),codepoints.end(),cp)) throw std::invalid_argument("Original character was not prepared");
            if(at==member.byteEnd && c.original==0 && c.marks.empty()) c.original=cp;
            else if(c.original==0) c.original=cp;
            else c.marks.push_back(cp);
            binding.maximumGlyphs++;
        }
        binding.characters.push_back(std::move(c));
    }
    auto& prepared=preparedLayouts_[layout.get()];prepared.owner=layout;
    prepared.characters.insert_or_assign(modifiers,std::move(binding));
}
TextMesh TextRenderer::mesh(const Layout& layout, float size, int alignment, const GlyphModifier* modifiers,
                            float tracking, float leading, float baselineShift,uint32_t fill,uint32_t stroke,float strokeWidth,float softness) {
    const auto started=std::chrono::steady_clock::now();
    if(!std::isfinite(size) || size<=0 || alignment<0 || alignment>2 || !std::isfinite(tracking) ||
        !std::isfinite(leading) || !std::isfinite(baselineShift)) throw std::invalid_argument("Invalid text mesh inputs");
    const auto& prepared=preparedLayouts_.at(&layout);
    if(prepared.generation!=stats_.generation) throw std::logic_error("Stale text preparation");
    const auto bindingFound=prepared.characters.find(modifiers);
    const auto* binding=bindingFound==prepared.characters.end()?nullptr:&bindingFound->second;
    const auto maximumGlyphs=binding?std::max(layout.glyphs.size(),binding->maximumGlyphs):layout.glyphs.size();
    if(vertices_.size()+maximumGlyphs*4>vertices_.capacity() || indices_.size()+maximumGlyphs*6>indices_.capacity())
        throw TextCapacityError("Text batch exceeds prepared mesh capacity");
    TextMesh result{static_cast<uint32_t>(indices_.size()),0};
    const double factor=static_cast<double>(size)/layout.upem;
    const double spacing=static_cast<double>(tracking)*size/1000.0;
    double maximum=0;
    for(size_t i=0;i<layout.lineAdvances.size();++i) maximum=std::max(maximum,
        layout.lineAdvance(i)*factor+std::max(0,static_cast<int>(layout.lineClusterCounts[i])-1)*spacing);
    const double lineHeight=(layout.styled?layout.styledAscent-layout.styledDescent+std::max(0.0,layout.styledLineGap):
        layout.ascent-layout.descent+std::max(0,layout.lineGap))*factor+leading;
    double pen=0; uint32_t line=0,previousCluster=UINT32_MAX,clusterOrdinal=0;
    for(size_t ordinal=0;ordinal<layout.glyphs.size();++ordinal) {
        const auto& glyph=layout.glyphs[ordinal];
        const auto* geometry=layout.styled?&layout.geometry[ordinal]:nullptr;
        if(glyph.line!=line) { line=glyph.line; pen=0; previousCluster=UINT32_MAX; clusterOrdinal=0; }
        const bool firstInCluster=previousCluster!=layout.glyphClusters[ordinal];
        if(previousCluster!=UINT32_MAX && firstInCluster) ++clusterOrdinal;
        previousCluster=layout.glyphClusters[ordinal];
        const auto& p=prepared.glyphs[ordinal];
        const auto cluster=layout.glyphClusters[ordinal];
        if(modifiers && modifiers[cluster].reserved[0]!=0) {
            if(!binding) throw std::logic_error("Character animation was not prepared");
            if(firstInCluster) {
                const auto& member=layout.clusters[cluster];const auto& modifier=modifiers[cluster];double characterPen=0;
                const double advance=layout.lineAdvance(line)*factor+std::max(0,static_cast<int>(layout.lineClusterCounts[line])-1)*spacing;
                const double align=(maximum-advance)*(alignment==0?0:alignment==1?.5:1);
                const double scaleX=geometry?geometry->scaleX:1,scaleY=geometry?geometry->scaleY:1;
                for(uint32_t character=member.character;character<member.characterEnd;++character) {
                    const auto& original=binding->characters[character];const auto& catalogue=*binding->catalogues[original.font];
                    const auto& catalogueMesh=preparedLayouts_.at(&catalogue);
                    if(catalogueMesh.generation!=stats_.generation) throw std::logic_error("Stale character catalogue");
                    for(size_t part=0;part<=original.marks.size();++part) {
                        const auto cp=part?original.marks[part-1]:static_cast<uint32_t>(binding->codes[character]);
                        const auto entry=std::lower_bound(binding->codepoints.begin(),binding->codepoints.end(),cp);
                        if(entry==binding->codepoints.end() || *entry!=cp) throw std::logic_error("Character codepoint was not prepared; narrow the animated range");
                        const auto index=static_cast<size_t>(entry-binding->codepoints.begin());const auto& sprite=catalogueMesh.glyphs[index];
                        if(sprite.sdf) {
                            const auto& sdf=*sprite.sdf;const double px=size*scaleX/sdf.ppem,py=size*scaleY/sdf.ppem;const float k=tiers::distanceScale(sdf.ppem);
                            const float x=static_cast<float>(pen+clusterOrdinal*spacing+align+characterPen+sdf.left*px);
                            const double baseline=line*lineHeight-baselineShift-(geometry?geometry->yOffset*factor:glyph.yOffset*factor);
                            const float y=static_cast<float>(baseline-sdf.top*py);const float w=static_cast<float>(sdf.width*px),h=static_cast<float>(sdf.height*py);
                            const float du=sdf.width/2048.0f,dv=sdf.height/2048.0f;const auto base=static_cast<uint32_t>(vertices_.size());
                            TextVertex quad[]={{x,y,sprite.u,sprite.v},{x+w,y,sprite.u+du,sprite.v},{x,y+h,sprite.u,sprite.v+dv},{x+w,y+h,sprite.u+du,sprite.v+dv}};
                            for(auto& vertex:quad) {
                                if(geometry) { vertex.x-=static_cast<float>(geometry->shear*(vertex.y-baseline));vertex.sdf[3]=static_cast<float>(geometry->bold)*k; }
                                const float xx=vertex.x+modifier.offsetX,yy=vertex.y+modifier.offsetY;
                                vertex.x=modifier.a*xx+modifier.c*yy+modifier.tx;vertex.y=modifier.b*xx+modifier.d*yy+modifier.ty;
                                std::copy(modifier.fill,modifier.fill+4,vertex.colour);std::copy(modifier.stroke,modifier.stroke+4,vertex.stroke);
                                vertex.page=static_cast<float>(sprite.page);vertex.sdf[0]=modifier.halfStroke*k;vertex.sdf[1]=modifier.softness*k;vertex.sdf[2]=modifier.opacity;
                                if(!std::isfinite(vertex.x) || !std::isfinite(vertex.y)) throw std::overflow_error("Character animation exceeds finite mesh geometry");
                                vertices_.push_back(vertex);
                            }
                            for(uint32_t i:{0u,1u,2u,1u,3u,2u}) indices_.push_back(base+i);
                        }
                        characterPen+=catalogue.geometry[index].advance*scaleX*size/catalogue.upem;
                    }
                }
            }
        } else if(p.sdf) {
            const auto& sdf=*p.sdf;
            const double advance=layout.lineAdvance(line)*factor+std::max(0,static_cast<int>(layout.lineClusterCounts[line])-1)*spacing;
            const double align=(maximum-advance)*(alignment==0?0:alignment==1?0.5:1);
            // SDF bitmap already includes the full spread padding: half-stroke + AA stays inside it.
            const double pixelX=size*(geometry?geometry->scaleX:1)/sdf.ppem;
            const double pixelY=size*(geometry?geometry->scaleY:1)/sdf.ppem;
            // Distances are encoded for the base tier; scale them to this glyph's tier.
            const float k=tiers::distanceScale(sdf.ppem);
            const float x=static_cast<float>(pen+clusterOrdinal*spacing+(geometry?geometry->xOffset:glyph.xOffset)*factor+align+sdf.left*pixelX);
            const float y=static_cast<float>(line*lineHeight-(geometry?geometry->yOffset:glyph.yOffset)*factor-sdf.top*pixelY-baselineShift);
            const float w=static_cast<float>(sdf.width*pixelX),h=static_cast<float>(sdf.height*pixelY);
            const float du=sdf.width/2048.0f,dv=sdf.height/2048.0f;
            const uint32_t base=static_cast<uint32_t>(vertices_.size());
            TextVertex quad[]={{x,y,p.u,p.v},{x+w,y,p.u+du,p.v},{x,y+h,p.u,p.v+dv},{x+w,y+h,p.u+du,p.v+dv}};
            for(auto& vertex:quad) {
                if(geometry) {
                    const double baseline=line*lineHeight-baselineShift+geometry->baseline*factor;
                    vertex.x-=static_cast<float>(geometry->shear*(vertex.y-baseline));
                    vertex.sdf[3]=static_cast<float>(geometry->bold)*k;
                }
                vertex.page=static_cast<float>(p.page);
                if(modifiers) { const auto& m=modifiers[layout.glyphClusters[ordinal]];
                    const float px=vertex.x+m.offsetX,py=vertex.y+m.offsetY;
                    vertex.x=m.a*px+m.c*py+m.tx;vertex.y=m.b*px+m.d*py+m.ty;
                    std::copy(m.fill,m.fill+4,vertex.colour);std::copy(m.stroke,m.stroke+4,vertex.stroke);
                    vertex.sdf[0]=m.halfStroke*k;vertex.sdf[1]=m.softness*k;vertex.sdf[2]=m.opacity;
                } else {
                    for(int channel=0;channel<4;++channel) {
                        const unsigned shift=channel==3?24:16-channel*8;
                        vertex.colour[channel]=((fill>>shift)&255)/255.0f;
                        vertex.stroke[channel]=((stroke>>shift)&255)/255.0f;
                    }
                    const float half=std::min(strokeWidth*.5f,size*.10f),soft=std::min(softness,std::max(0.0f,size*.10f-half));
                    const float distanceScale=static_cast<float>(sdf.ppem)/(2.0f*sdf.spread*size);
                    vertex.sdf[0]=half*distanceScale;vertex.sdf[1]=soft*distanceScale;vertex.sdf[2]=1;
                }
                if(!std::isfinite(vertex.x) || !std::isfinite(vertex.y))
                    throw std::overflow_error("Text animator arithmetic exceeds finite mesh geometry");
                vertices_.push_back(vertex);
            }
            for(uint32_t index:{0u,1u,2u,1u,3u,2u}) indices_.push_back(base+index);
        }
        pen+=(geometry?geometry->advance:glyph.xAdvance)*factor;
    }
    result.indexCount=static_cast<uint32_t>(indices_.size())-result.firstIndex;
    stats_.meshBuildNs=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-started).count();
    return result;
}
void TextRenderer::upload(VkCommandBuffer command) {
    if(!image_) return;
    if(dirty_) {
        const auto uploadStart=std::chrono::steady_clock::now();
        const VkDeviceSize pageBytes=2048u*2048u;
        const VkDeviceSize bytes=pageBytes*allocatedPages_;
        buffer(staging_,bytes,VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        std::memset(staging_.mapped,0,static_cast<size_t>(bytes));
        for(unsigned page=0;page<packed_.pages.size();++page)
            std::memcpy(static_cast<uint8_t*>(staging_.mapped)+page*pageBytes,packed_.pages[page].pixels.data(),static_cast<size_t>(pageBytes));
        flush(staging_);
        VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER}; barrier.image=image_;
        barrier.oldLayout=initialized_?VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL:VK_IMAGE_LAYOUT_UNDEFINED;
        barrier.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        barrier.srcAccessMask=initialized_?VK_ACCESS_SHADER_READ_BIT:0; barrier.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.srcQueueFamilyIndex=barrier.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
        barrier.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,allocatedPages_};
        vkCmdPipelineBarrier(command,initialized_?VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT:VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,nullptr,0,nullptr,1,&barrier);
        std::vector<VkBufferImageCopy> regions(allocatedPages_);
        for(unsigned page=0;page<allocatedPages_;++page) { auto& region=regions[page]; region.bufferOffset=page*pageBytes;
            region.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,page,1}; region.imageExtent={2048,2048,1}; }
        vkCmdCopyBufferToImage(command,staging_.buffer,image_,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,allocatedPages_,regions.data());
        barrier.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; barrier.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        barrier.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT; barrier.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,0,0,nullptr,0,nullptr,1,&barrier);
        initialized_=true; dirty_=false; ++stats_.uploads; stats_.uploadBytes+=bytes;
        stats_.atlasUploadCpuNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-uploadStart).count();
    }
    buffer(vertex_,vertices_.size()*sizeof(TextVertex),VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
    buffer(index_,indices_.size()*sizeof(uint32_t),VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
    if(!vertices_.empty()) { std::memcpy(vertex_.mapped,vertices_.data(),vertices_.size()*sizeof(TextVertex)); flush(vertex_); }
    if(!indices_.empty()) { std::memcpy(index_.mapped,indices_.data(),indices_.size()*sizeof(uint32_t)); flush(index_); }
}
void TextRenderer::draw(VkCommandBuffer command, const TextMesh& mesh, const TextPush& push) {
    if(mesh.indexCount==0) return;
    vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline_);
    vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipelineLayout_,0,1,&descriptor_,0,nullptr);
    const VkDeviceSize offset=0; vkCmdBindVertexBuffers(command,0,1,&vertex_.buffer,&offset);
    vkCmdBindIndexBuffer(command,index_.buffer,0,VK_INDEX_TYPE_UINT32);
    vkCmdPushConstants(command,pipelineLayout_,VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,sizeof(push),&push);
    vkCmdDrawIndexed(command,mesh.indexCount,1,mesh.firstIndex,0,0); ++stats_.draws;
}
}
