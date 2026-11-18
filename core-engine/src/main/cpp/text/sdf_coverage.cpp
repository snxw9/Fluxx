#include "sdf_coverage.h"
#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace fluxx::text::coverage_sdf {
namespace {
constexpr float Far = 1e20f;

// Felzenszwalb & Huttenlocher 1D squared distance transform of f (length n) into d; v/z are scratch.
void transform1d(const float* f, float* d, int n, int* v, double* z) {
    const auto meet = [&](int q, int p) {
        return ((static_cast<double>(f[q]) + double(q) * q) - (static_cast<double>(f[p]) + double(p) * p)) / (2.0 * (q - p));
    };
    int k = 0; v[0] = 0; z[0] = -1e300; z[1] = 1e300;
    for (int q = 1; q < n; ++q) {
        double s = meet(q, v[k]);
        while (s <= z[k]) { --k; s = meet(q, v[k]); }
        ++k; v[k] = q; z[k] = s; z[k + 1] = 1e300;
    }
    k = 0;
    for (int q = 0; q < n; ++q) {
        while (z[k + 1] < q) ++k;
        const double delta = q - v[k];
        d[q] = static_cast<float>(delta * delta + f[v[k]]);
    }
}

// Squared Euclidean distance from every pixel to the nearest pixel where target(mask) is true.
std::vector<float> squaredDistance(const std::vector<uint8_t>& mask, int w, int h, bool insideTargets) {
    std::vector<float> grid(static_cast<std::size_t>(w) * h);
    for (std::size_t i = 0; i < grid.size(); ++i) grid[i] = ((mask[i] != 0) == insideTargets) ? 0.0f : Far;
    const int n = std::max(w, h);
    std::vector<float> f(n), d(n);
    std::vector<double> z(static_cast<std::size_t>(n) + 1);
    std::vector<int> v(n);
    for (int x = 0; x < w; ++x) {
        for (int y = 0; y < h; ++y) f[y] = grid[static_cast<std::size_t>(y) * w + x];
        transform1d(f.data(), d.data(), h, v.data(), z.data());
        for (int y = 0; y < h; ++y) grid[static_cast<std::size_t>(y) * w + x] = d[y];
    }
    for (int y = 0; y < h; ++y) {
        float* row = grid.data() + static_cast<std::size_t>(y) * w;
        std::copy_n(row, w, f.data());
        transform1d(f.data(), d.data(), w, v.data(), z.data());
        std::copy_n(d.data(), w, row);
    }
    return grid;
}
}

unsigned supersample(int width, int height) {
    if (width <= 0 || height <= 0) return 1;
    for (unsigned k = MaxSupersample; k > 1; --k)
        if (static_cast<std::size_t>(width) * k * static_cast<std::size_t>(height) * k <= MaxMaskPixels) return k;
    return 1;
}

uint8_t encode(double distance, unsigned spread) {
    if (spread == 0) throw std::invalid_argument("SDF spread must be positive");
    // FreeType: DivFix(dist, spread) in 16.16, >> 9 => floor(|d| / spread * 128); 127 / 128 clamps.
    double magnitude = std::floor(std::fabs(distance) / spread * 128.0);
    if (distance >= 0) return static_cast<uint8_t>(128 + std::min(magnitude, 127.0));
    return static_cast<uint8_t>(128 - std::min(magnitude, 128.0));
}

Check compare(const std::vector<uint8_t>& sdf, const std::vector<uint8_t>& mask, int width, int height, unsigned k) {
    const int mw = width * static_cast<int>(k);
    if (sdf.size() != static_cast<std::size_t>(width) * height || mask.size() != static_cast<std::size_t>(mw) * height * k)
        throw std::invalid_argument("SDF / coverage size mismatch");
    Check result;
    const unsigned block = k * k;
    for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x) {
        unsigned inside = 0;
        for (unsigned j = 0; j < k; ++j) for (unsigned i = 0; i < k; ++i)
            inside += mask[(static_cast<std::size_t>(y) * k + j) * mw + static_cast<std::size_t>(x) * k + i] != 0;
        const bool sdfInside = sdf[static_cast<std::size_t>(y) * width + x] >= 128;
        if (inside * 8 >= block * 7 && !sdfInside) ++result.holes;
        if (inside == 0 && sdfInside) ++result.spills;
    }
    return result;
}

std::vector<uint8_t> fromMask(const std::vector<uint8_t>& mask, int width, int height, unsigned k, unsigned spread) {
    const int mw = width * static_cast<int>(k), mh = height * static_cast<int>(k);
    if (k == 0 || mask.size() != static_cast<std::size_t>(mw) * mh) throw std::invalid_argument("Invalid coverage mask");
    const auto toInside = squaredDistance(mask, mw, mh, true);
    const auto toOutside = squaredDistance(mask, mw, mh, false);
    // Signed distance (supersampled pixels) at a mask pixel centre: the edge lies half a pixel before the nearest
    // pixel of the other class.
    const auto signedAt = [&](int x, int y) {
        const std::size_t i = static_cast<std::size_t>(y) * mw + x;
        return mask[i] ? std::sqrt(static_cast<double>(toOutside[i])) - 0.5 : -(std::sqrt(static_cast<double>(toInside[i])) - 0.5);
    };
    std::vector<uint8_t> out(static_cast<std::size_t>(width) * height);
    const int half = static_cast<int>(k) / 2;
    for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x) {
        // The output pixel centre is the centre of its k x k block: average the central 2 x 2 (k even) or the centre (odd).
        const int cx = x * static_cast<int>(k) + half, cy = y * static_cast<int>(k) + half;
        double d;
        if (k % 2 == 0) d = (signedAt(cx - 1, cy - 1) + signedAt(cx, cy - 1) + signedAt(cx - 1, cy) + signedAt(cx, cy)) / 4.0;
        else d = signedAt(cx, cy);
        out[static_cast<std::size_t>(y) * width + x] = encode(d / k, spread);
    }
    return out;
}

}
