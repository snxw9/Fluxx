#include "text_renderer.h"
#include "font_catalog.h"
#include "text_shaders.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <cstddef>

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
    VkPipelineCache cache, AAssetManager* assets):physical_(physical),device_(device),pass_(pass),cache_(cache),assets_(assets),
    fonts_(acquireFontCatalog(assets)) {}
TextRenderer::~TextRenderer() {
    // Owner has completed its work fence / device idle before releasing this object.
    release(staging_); release(vertex_); release(index_);
    if(pipeline_) vkDestroyPipeline(device_,pipeline_,nullptr);
    if(pipelineLayout_) vkDestroyPipelineLayout(device_,pipelineLayout_,nullptr);
    if(pool_) vkDestroyDescriptorPool(device_,pool_,nullptr);
    if(descriptorLayout_) vkDestroyDescriptorSetLayout(device_,descriptorLayout_,nullptr);
    if(sampler_) vkDestroySampler(device_,sampler_,nullptr);
    if(view_) vkDestroyImageView(device_,view_,nullptr);
    if(image_) vkDestroyImage(device_,image_,nullptr);
    if(memory_) vkFreeMemory(device_,memory_,nullptr);
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
    VkFormatProperties format; vkGetPhysicalDeviceFormatProperties(physical_,VK_FORMAT_R8_UNORM,&format);
    const auto required=VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT|VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT|VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
    if((format.optimalTilingFeatures&required)!=required) throw std::runtime_error("R8 linear-filtered atlas unsupported");
    VkPhysicalDeviceProperties props; vkGetPhysicalDeviceProperties(physical_,&props);
    if(props.limits.maxImageDimension2D<2048) throw TextCapacityError("2048 text atlas unsupported");
    VkImageCreateInfo image{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO}; image.imageType=VK_IMAGE_TYPE_2D;
    image.format=VK_FORMAT_R8_UNORM; image.extent={2048,2048,1}; image.mipLevels=1; image.arrayLayers=1;
    image.samples=VK_SAMPLE_COUNT_1_BIT; image.tiling=VK_IMAGE_TILING_OPTIMAL;
    image.usage=VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT; image.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    check(vkCreateImage(device_,&image,nullptr,&image_),"text atlas"); ++stats_.images;
    VkMemoryRequirements requirements; vkGetImageMemoryRequirements(device_,image_,&requirements);
    VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; allocation.allocationSize=requirements.size;
    allocation.memoryTypeIndex=memoryType(requirements.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    check(vkAllocateMemory(device_,&allocation,nullptr,&memory_),"text atlas memory"); ++stats_.memories; stats_.bytes+=requirements.size;
    check(vkBindImageMemory(device_,image_,memory_,0),"text atlas bind");
    VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO}; view.image=image_; view.viewType=VK_IMAGE_VIEW_TYPE_2D;
    view.format=VK_FORMAT_R8_UNORM; view.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    check(vkCreateImageView(device_,&view,nullptr,&view_),"text atlas view"); ++stats_.views;
    VkSamplerCreateInfo sampler{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO}; sampler.magFilter=VK_FILTER_LINEAR; sampler.minFilter=VK_FILTER_LINEAR;
    sampler.mipmapMode=VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sampler.addressModeU=sampler.addressModeV=sampler.addressModeW=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    check(vkCreateSampler(device_,&sampler,nullptr,&sampler_),"text sampler"); ++stats_.samplers;
    createPipeline();
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
            {1,0,VK_FORMAT_R32G32_SFLOAT,offsetof(TextVertex,u)},{2,0,VK_FORMAT_R32G32B32A32_SFLOAT,offsetof(TextVertex,colour)}};
        VkPipelineVertexInputStateCreateInfo input{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
        input.vertexBindingDescriptionCount=1; input.pVertexBindingDescriptions=&bindingDescription;
        input.vertexAttributeDescriptionCount=3; input.pVertexAttributeDescriptions=attributes;
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
std::shared_ptr<const Layout> TextRenderer::layout(const std::string& font, const std::string& utf8) { return fonts_->shape(font,utf8); }
void TextRenderer::begin() { required_.clear(); vertices_.clear(); indices_.clear(); }
void TextRenderer::collect(const std::shared_ptr<const Layout>& layout) {
    for(const auto& glyph:layout->glyphs) {
        Key key{layout->fontId,glyph.id};
        if(required_.count(key)) continue;
        auto found=retained_.find(key);
        required_[key]=found==retained_.end()?fonts_->sdf(key.first,key.second):found->second;
    }
}
void TextRenderer::pack() {
    bool misses=false; for(const auto& entry:required_) if(!retained_.count(entry.first)) { misses=true; break; }
    if(!misses) return;
    std::map<Key,std::shared_ptr<const SdfGlyph>> candidates=retained_;
    candidates.insert(required_.begin(),required_.end());
    auto packEntries=[](const auto& entries) { std::vector<std::shared_ptr<const SdfGlyph>> glyphs;
        for(const auto& entry:entries) glyphs.push_back(entry.second); return packGlyphs(std::move(glyphs),2048); };
    auto packed=packEntries(candidates);
    if(packed.rejected) { candidates=required_; packed=packEntries(candidates); }
    if(packed.rejected) throw TextCapacityError("Required glyph set exceeds the 2048 atlas (E1 proof capacity)");
    createAtlas(); retained_=std::move(candidates); packed_=std::move(packed); placements_.clear();
    for(const auto& placement:packed_.placements) placements_.emplace(Key{placement.glyph->fontId,placement.glyph->glyphId},placement);
    dirty_=true; ++stats_.generation;
}
TextMesh TextRenderer::mesh(const Layout& layout, float size, int alignment, const std::vector<GlyphModifier>* modifiers) {
    if(!std::isfinite(size) || size<=0 || alignment<0 || alignment>2) throw std::invalid_argument("Invalid text mesh inputs");
    if(modifiers && modifiers->size()!=layout.glyphs.size()) throw std::invalid_argument("Glyph modifier count mismatch");
    TextMesh result{static_cast<uint32_t>(indices_.size()),0};
    const double factor=static_cast<double>(size)/layout.upem;
    const int64_t maximum=layout.lineAdvances.empty()?0:*std::max_element(layout.lineAdvances.begin(),layout.lineAdvances.end());
    const double lineHeight=(layout.ascent-layout.descent+std::max(0,layout.lineGap))*factor;
    double pen=0; uint32_t line=0; size_t ordinal=0;
    for(const auto& glyph:layout.glyphs) {
        if(glyph.line!=line) { line=glyph.line; pen=0; }
        const auto found=placements_.find({layout.fontId,glyph.id});
        if(found!=placements_.end()) {
            const auto& p=found->second; const auto& sdf=*p.glyph;
            const double align=(maximum-layout.lineAdvances[line])*factor*(alignment==0?0:alignment==1?0.5:1);
            const float x=static_cast<float>(pen+(glyph.xOffset*factor)+align+sdf.left*size/48);
            const float y=static_cast<float>(line*lineHeight-glyph.yOffset*factor-sdf.top*size/48);
            const float w=sdf.width*size/48, h=sdf.height*size/48;
            const float u=p.x/2048.0f,v=p.y/2048.0f,du=sdf.width/2048.0f,dv=sdf.height/2048.0f;
            const uint32_t base=static_cast<uint32_t>(vertices_.size());
            TextVertex quad[]={{x,y,u,v},{x+w,y,u+du,v},{x,y+h,u,v+dv},{x+w,y+h,u+du,v+dv}};
            for(auto& vertex:quad) {
                if(modifiers) { const auto& m=(*modifiers)[ordinal]; const float px=vertex.x-x,py=vertex.y-y;
                    vertex.x=x+m.a*px+m.c*py+m.tx; vertex.y=y+m.b*px+m.d*py+m.ty;
                    for(int c=0;c<4;++c) vertex.colour[c]=m.rgba[c]; vertex.colour[3]*=m.opacity; }
                vertices_.push_back(vertex);
            }
            for(uint32_t index:{0u,1u,2u,1u,3u,2u}) indices_.push_back(base+index);
        } else if(required_.at({layout.fontId,glyph.id})->width) throw std::runtime_error("Missing prepared glyph");
        pen+=glyph.xAdvance*factor; ++ordinal;
    }
    result.indexCount=static_cast<uint32_t>(indices_.size())-result.firstIndex; return result;
}
void TextRenderer::upload(VkCommandBuffer command) {
    if(!image_) return;
    if(dirty_) {
        buffer(staging_,packed_.pixels.size(),VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        std::memcpy(staging_.mapped,packed_.pixels.data(),packed_.pixels.size()); flush(staging_);
        VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER}; barrier.image=image_;
        barrier.oldLayout=initialized_?VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL:VK_IMAGE_LAYOUT_UNDEFINED;
        barrier.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        barrier.srcAccessMask=initialized_?VK_ACCESS_SHADER_READ_BIT:0; barrier.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.srcQueueFamilyIndex=barrier.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
        barrier.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        vkCmdPipelineBarrier(command,initialized_?VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT:VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,nullptr,0,nullptr,1,&barrier);
        VkBufferImageCopy region{}; region.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1}; region.imageExtent={2048,2048,1};
        vkCmdCopyBufferToImage(command,staging_.buffer,image_,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&region);
        barrier.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; barrier.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        barrier.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT; barrier.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,0,0,nullptr,0,nullptr,1,&barrier);
        initialized_=true; dirty_=false; ++stats_.uploads; stats_.uploadBytes+=packed_.pixels.size();
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
