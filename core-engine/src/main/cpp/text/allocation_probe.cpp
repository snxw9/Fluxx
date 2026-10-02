#include "allocation_probe.h"
#include <cstdlib>
#include <new>
namespace { thread_local uint64_t calls=0; }
static void* allocate(size_t bytes) {
    ++calls;
    if(auto* p=std::malloc(bytes?bytes:1)) return p;
    throw std::bad_alloc();
}
void* operator new(size_t bytes) { return allocate(bytes); }
void* operator new[](size_t bytes) { return allocate(bytes); }
void operator delete(void* p) noexcept { std::free(p); }
void operator delete[](void* p) noexcept { std::free(p); }
void operator delete(void* p,size_t) noexcept { std::free(p); }
void operator delete[](void* p,size_t) noexcept { std::free(p); }
void* operator new(size_t bytes,const std::nothrow_t&) noexcept { try { return allocate(bytes); } catch(...) { return nullptr; } }
void* operator new[](size_t bytes,const std::nothrow_t&) noexcept { try { return allocate(bytes); } catch(...) { return nullptr; } }
void operator delete(void* p,const std::nothrow_t&) noexcept { std::free(p); }
void operator delete[](void* p,const std::nothrow_t&) noexcept { std::free(p); }
static void* aligned(size_t bytes,std::align_val_t alignment) {
    ++calls; void* p=nullptr;
    if(posix_memalign(&p,static_cast<size_t>(alignment),bytes?bytes:1)==0) return p;
    throw std::bad_alloc();
}
void* operator new(size_t bytes,std::align_val_t a) { return aligned(bytes,a); }
void* operator new[](size_t bytes,std::align_val_t a) { return aligned(bytes,a); }
void operator delete(void* p,std::align_val_t) noexcept { std::free(p); }
void operator delete[](void* p,std::align_val_t) noexcept { std::free(p); }
void operator delete(void* p,size_t,std::align_val_t) noexcept { std::free(p); }
void operator delete[](void* p,size_t,std::align_val_t) noexcept { std::free(p); }
void* operator new(size_t bytes,std::align_val_t a,const std::nothrow_t&) noexcept { try { return aligned(bytes,a); } catch(...) { return nullptr; } }
void* operator new[](size_t bytes,std::align_val_t a,const std::nothrow_t&) noexcept { try { return aligned(bytes,a); } catch(...) { return nullptr; } }
void operator delete(void* p,std::align_val_t,const std::nothrow_t&) noexcept { std::free(p); }
void operator delete[](void* p,std::align_val_t,const std::nothrow_t&) noexcept { std::free(p); }
namespace fluxx::text::debug {
uint64_t allocationCount() noexcept { return calls; }
uint64_t allocationCanary() {
    const auto before=calls;
    // Explicit call cannot be removed through new-expression allocation elision.
    auto* p=::operator new(31); ::operator delete(p); return calls-before;
}
}
