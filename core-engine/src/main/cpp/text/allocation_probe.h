#pragma once
#include <cstdint>
namespace fluxx::text::debug {
// Thread-local, debug-only count of C++ new calls in libfluxxengine; excludes malloc/driver allocations.
uint64_t allocationCount() noexcept;
uint64_t allocationCanary();
}
