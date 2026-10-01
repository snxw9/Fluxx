#pragma once
#include <vulkan/vulkan.h>
#include <map>
#include <mutex>
#include <sstream>
#include <cstdint>
#include <atomic>

// Debug-only allocation ledger, independent of renderer handle snapshots. Track successful
// creates and actual destroys, including failed initialization/partially created pipelines.
#ifdef FLUXX_TEXT_DIAGNOSTICS
namespace fluxx::debug {
inline std::atomic<uint64_t> validationErrors{0};
struct Ledger {
    std::mutex mutex;
    std::map<std::string,std::map<uint64_t,uint64_t>> handles;
    std::map<uint64_t,uint64_t> descriptorPools;
    template<class T> static uint64_t key(T handle) { return (uint64_t)handle; }
    template<class T> void add(const char* category,T handle,uint64_t bytes=0) {
        if(!handle) return; std::lock_guard<std::mutex> lock(mutex); handles[category][key(handle)]=bytes;
    }
    template<class T> void remove(const char* category,T handle) {
        std::lock_guard<std::mutex> lock(mutex); handles[category].erase(key(handle));
    }
    std::string json() {
        std::lock_guard<std::mutex> lock(mutex); std::ostringstream out; out << "{"; bool comma=false;
        for(const char* category:{"images","views","samplers","buffers","memories","descriptorPools","descriptorSets",
            "descriptorLayouts","pipelines","pipelineLayouts","shaderModules","fences","semaphores","framebuffers","renderPasses","pipelineCaches","commandPools"}) {
            if(comma) out << ','; comma=true; out << '"' << category << "\":" << handles[category].size();
        }
        uint64_t bytes=0; for(const auto& memory:handles["memories"]) bytes+=memory.second;
        out << ",\"bytes\":" << bytes << ",\"validationErrors\":" << validationErrors.load() << "}"; return out.str();
    }
};
inline Ledger ledger;
#define FLUXX_CREATE(Name, Info, Handle, Category) \
inline VkResult Name(VkDevice device,const Info* info,const VkAllocationCallbacks* callbacks,Handle* output) { \
    auto result=::vk##Name(device,info,callbacks,output); if(result==VK_SUCCESS) ledger.add(Category,*output); return result; }
#define FLUXX_DESTROY(Name, Handle, Category) \
inline void Name(VkDevice device,Handle handle,const VkAllocationCallbacks* callbacks) { \
    ::vk##Name(device,handle,callbacks); ledger.remove(Category,handle); }
FLUXX_CREATE(CreateImage,VkImageCreateInfo,VkImage,"images")
FLUXX_DESTROY(DestroyImage,VkImage,"images")
FLUXX_CREATE(CreateImageView,VkImageViewCreateInfo,VkImageView,"views")
FLUXX_DESTROY(DestroyImageView,VkImageView,"views")
FLUXX_CREATE(CreateSampler,VkSamplerCreateInfo,VkSampler,"samplers")
FLUXX_DESTROY(DestroySampler,VkSampler,"samplers")
FLUXX_CREATE(CreateBuffer,VkBufferCreateInfo,VkBuffer,"buffers")
FLUXX_DESTROY(DestroyBuffer,VkBuffer,"buffers")
FLUXX_CREATE(CreateDescriptorPool,VkDescriptorPoolCreateInfo,VkDescriptorPool,"descriptorPools")
FLUXX_CREATE(CreateDescriptorSetLayout,VkDescriptorSetLayoutCreateInfo,VkDescriptorSetLayout,"descriptorLayouts")
FLUXX_DESTROY(DestroyDescriptorSetLayout,VkDescriptorSetLayout,"descriptorLayouts")
FLUXX_CREATE(CreatePipelineLayout,VkPipelineLayoutCreateInfo,VkPipelineLayout,"pipelineLayouts")
FLUXX_DESTROY(DestroyPipelineLayout,VkPipelineLayout,"pipelineLayouts")
FLUXX_DESTROY(DestroyPipeline,VkPipeline,"pipelines")
FLUXX_CREATE(CreateShaderModule,VkShaderModuleCreateInfo,VkShaderModule,"shaderModules")
FLUXX_DESTROY(DestroyShaderModule,VkShaderModule,"shaderModules")
FLUXX_CREATE(CreateFence,VkFenceCreateInfo,VkFence,"fences")
FLUXX_DESTROY(DestroyFence,VkFence,"fences")
FLUXX_CREATE(CreateSemaphore,VkSemaphoreCreateInfo,VkSemaphore,"semaphores")
FLUXX_DESTROY(DestroySemaphore,VkSemaphore,"semaphores")
FLUXX_CREATE(CreateFramebuffer,VkFramebufferCreateInfo,VkFramebuffer,"framebuffers")
FLUXX_DESTROY(DestroyFramebuffer,VkFramebuffer,"framebuffers")
FLUXX_CREATE(CreateRenderPass,VkRenderPassCreateInfo,VkRenderPass,"renderPasses")
FLUXX_DESTROY(DestroyRenderPass,VkRenderPass,"renderPasses")
FLUXX_CREATE(CreatePipelineCache,VkPipelineCacheCreateInfo,VkPipelineCache,"pipelineCaches")
FLUXX_DESTROY(DestroyPipelineCache,VkPipelineCache,"pipelineCaches")
FLUXX_CREATE(CreateCommandPool,VkCommandPoolCreateInfo,VkCommandPool,"commandPools")
FLUXX_DESTROY(DestroyCommandPool,VkCommandPool,"commandPools")
#undef FLUXX_CREATE
#undef FLUXX_DESTROY
inline VkResult AllocateMemory(VkDevice device,const VkMemoryAllocateInfo* info,const VkAllocationCallbacks* callbacks,VkDeviceMemory* output) {
    auto result=::vkAllocateMemory(device,info,callbacks,output);
    if(result==VK_SUCCESS) ledger.add("memories",*output,info->allocationSize); return result;
}
inline void FreeMemory(VkDevice device,VkDeviceMemory memory,const VkAllocationCallbacks* callbacks) {
    ::vkFreeMemory(device,memory,callbacks); ledger.remove("memories",memory);
}
inline VkResult AllocateDescriptorSets(VkDevice device,const VkDescriptorSetAllocateInfo* info,VkDescriptorSet* output) {
    auto result=::vkAllocateDescriptorSets(device,info,output);
    if(result==VK_SUCCESS) {
        std::lock_guard<std::mutex> lock(ledger.mutex);
        for(uint32_t i=0;i<info->descriptorSetCount;++i) {
            ledger.handles["descriptorSets"][Ledger::key(output[i])]=0;
            ledger.descriptorPools[Ledger::key(output[i])]=Ledger::key(info->descriptorPool);
        }
    }
    return result;
}
inline void DestroyDescriptorPool(VkDevice device,VkDescriptorPool pool,const VkAllocationCallbacks* callbacks) {
    ::vkDestroyDescriptorPool(device,pool,callbacks);
    std::lock_guard<std::mutex> lock(ledger.mutex);
    for(auto it=ledger.descriptorPools.begin();it!=ledger.descriptorPools.end();) {
        if(it->second==Ledger::key(pool)) { ledger.handles["descriptorSets"].erase(it->first); it=ledger.descriptorPools.erase(it); }
        else ++it;
    }
    ledger.handles["descriptorPools"].erase(Ledger::key(pool));
}
inline VkResult CreateGraphicsPipelines(VkDevice device,VkPipelineCache cache,uint32_t count,const VkGraphicsPipelineCreateInfo* info,
    const VkAllocationCallbacks* callbacks,VkPipeline* output) {
    auto result=::vkCreateGraphicsPipelines(device,cache,count,info,callbacks,output);
    for(uint32_t i=0;i<count;++i) if(output[i]) ledger.add("pipelines",output[i]); return result;
}
}
#define vkCreateImage fluxx::debug::CreateImage
#define vkDestroyImage fluxx::debug::DestroyImage
#define vkCreateImageView fluxx::debug::CreateImageView
#define vkDestroyImageView fluxx::debug::DestroyImageView
#define vkCreateSampler fluxx::debug::CreateSampler
#define vkDestroySampler fluxx::debug::DestroySampler
#define vkCreateBuffer fluxx::debug::CreateBuffer
#define vkDestroyBuffer fluxx::debug::DestroyBuffer
#define vkAllocateMemory fluxx::debug::AllocateMemory
#define vkFreeMemory fluxx::debug::FreeMemory
#define vkCreateDescriptorPool fluxx::debug::CreateDescriptorPool
#define vkDestroyDescriptorPool fluxx::debug::DestroyDescriptorPool
#define vkAllocateDescriptorSets fluxx::debug::AllocateDescriptorSets
#define vkCreateDescriptorSetLayout fluxx::debug::CreateDescriptorSetLayout
#define vkDestroyDescriptorSetLayout fluxx::debug::DestroyDescriptorSetLayout
#define vkCreatePipelineLayout fluxx::debug::CreatePipelineLayout
#define vkDestroyPipelineLayout fluxx::debug::DestroyPipelineLayout
#define vkCreateGraphicsPipelines fluxx::debug::CreateGraphicsPipelines
#define vkDestroyPipeline fluxx::debug::DestroyPipeline
#define vkCreateShaderModule fluxx::debug::CreateShaderModule
#define vkDestroyShaderModule fluxx::debug::DestroyShaderModule
#define vkCreateFence fluxx::debug::CreateFence
#define vkDestroyFence fluxx::debug::DestroyFence
#define vkCreateSemaphore fluxx::debug::CreateSemaphore
#define vkDestroySemaphore fluxx::debug::DestroySemaphore
#define vkCreateFramebuffer fluxx::debug::CreateFramebuffer
#define vkDestroyFramebuffer fluxx::debug::DestroyFramebuffer
#define vkCreateRenderPass fluxx::debug::CreateRenderPass
#define vkDestroyRenderPass fluxx::debug::DestroyRenderPass
#define vkCreatePipelineCache fluxx::debug::CreatePipelineCache
#define vkDestroyPipelineCache fluxx::debug::DestroyPipelineCache
#define vkCreateCommandPool fluxx::debug::CreateCommandPool
#define vkDestroyCommandPool fluxx::debug::DestroyCommandPool
#endif
