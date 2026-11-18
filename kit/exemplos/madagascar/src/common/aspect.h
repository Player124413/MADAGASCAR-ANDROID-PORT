#pragma once
#include <algorithm>
#include <atomic>

#include "app/app.h"
#include "common/options.h"

namespace game {

// The launcher chooses either the original 4:3 television frame or the existing full-screen phone stretch. The
// cached key includes that choice so changing it before a launch cannot leave an old wide viewport in use.
inline float Aspect() {
    static std::atomic<uint64_t> key{~0ull};
    static std::atomic<float> aspect{16.0f / 9.0f};
    const app::Settings& s = app::Get();
    uint32_t mode = std::min(Opt().aspect_ratio, 1u);
    uint64_t k = uint64_t(s.width) << 32 | uint64_t(s.height) << 1 | mode;
    if (key.exchange(k) != k) {
        app::Mode m = app::Output();
        aspect = mode ? 4.0f / 3.0f : std::max(float(m.width) / std::max(m.height, 1u), 4.0f / 3.0f);
    }
    return aspect;
}
// the share of the frame's width a 4:3 picture takes
inline float Narrow() { return 4.0f / 3.0f / Aspect(); }
inline bool Wide() { return Aspect() > 1.34f; }

}  // namespace game
