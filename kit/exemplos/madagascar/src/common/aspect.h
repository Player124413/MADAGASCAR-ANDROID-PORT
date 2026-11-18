#pragma once
#include <algorithm>
#include <atomic>

#include "app/app.h"
#include "common/options.h"

namespace game {

// The Android launcher offers a standard 16:9 frame and a 21:9 ultrawide frame. The cached key includes that choice
// so changing it before launch cannot leave an old viewport in use.
inline float Aspect() {
    static std::atomic<uint32_t> key{~0u};
    static std::atomic<float> aspect{16.0f / 9.0f};
    uint32_t mode = std::min(Opt().aspect_ratio, 1u);
    if (key.exchange(mode) != mode) aspect = mode ? 21.0f / 9.0f : 16.0f / 9.0f;
    return aspect;
}
// The picture always fills the chosen widescreen presentation.
inline float Narrow() { return 4.0f / 3.0f / Aspect(); }
inline bool Wide() { return true; }

}  // namespace game
