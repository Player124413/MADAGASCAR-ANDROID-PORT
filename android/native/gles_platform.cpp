// Android/EGL glue shared by the optional GLES backend. Vulkan continues to own its SDL surface independently.
#include "gpu/android_platform.h"
#include "gpu/host_common.h"

#include <SDL3/SDL.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <thread>

#include "app/app.h"
#include "common/aspect.h"
#include "common/options.h"
#include "host.h"

namespace gpu {

namespace {

using WindowProvider = ANativeWindow* (*)();
std::atomic<ANativeWindow*> g_window{nullptr};
std::atomic<WindowProvider> g_provider{nullptr};
std::atomic<uint32_t> g_frame_limit{0};
std::atomic<int> g_display_mode{kDisplay16x9};
std::atomic<float> g_fps{0};

}  // namespace

void SetAndroidWindow(ANativeWindow* window) { g_window = window; }
ANativeWindow* GetAndroidWindow() { return CurrentAndroidWindow(); }
void SetAndroidWindowProvider(ANativeWindow* (*provider)()) { g_provider = provider; }
ANativeWindow* CurrentAndroidWindow() {
    if (WindowProvider provider = g_provider.load()) {
        if (ANativeWindow* window = provider()) g_window = window;
        return g_window;
    }
    // This file also defines gpu::host helpers for GLES presentation; qualify the SDL host namespace explicitly.
    SDL_Window* window = ::host::Window();
    if (!window) return nullptr;
    SDL_PropertiesID properties = SDL_GetWindowProperties(window);
    ANativeWindow* native = static_cast<ANativeWindow*>(
        SDL_GetPointerProperty(properties, SDL_PROP_WINDOW_ANDROID_WINDOW_POINTER, nullptr));
    if (native) g_window = native;
    return native;
}

void SetDisplayMode(int mode) { g_display_mode = std::clamp(mode, int(kDisplayFill), int(kDisplay4x3)); }
int GetDisplayMode() { return g_display_mode.load(); }
float DisplayAspect(uint32_t, uint32_t) { return game::Aspect(); }
void SetFrameLimit(uint32_t fps) { g_frame_limit = fps; }
float GetFps() { return g_fps.load(); }
void SetResolutionPercent(uint32_t) {}
uint32_t GetResolutionPercent() { return dynres::Percent(); }

namespace host {

PictureRect PictureOnScreen(uint32_t screenWidth, uint32_t screenHeight) {
    const float aspect = game::Aspect();
    const float width = std::min(float(screenWidth), float(screenHeight) * aspect);
    const int32_t pictureWidth = int32_t(width);
    const int32_t pictureHeight = int32_t(width / aspect);
    return {(int32_t(screenWidth) - pictureWidth) / 2, (int32_t(screenHeight) - pictureHeight) / 2, pictureWidth,
            pictureHeight};
}

void RenderScale(uint32_t, uint32_t, uint32_t, uint32_t guestHeight, float& sx, float& sy) {
    // Use the same dynamic-resolution controller as Vulkan. The game redraws targets after a scale change.
    const float scale = app::RenderScale(guestHeight);
    sx = scale;
    sy = scale;
}

void FramePacer::Pace() {
    const PerfClock::time_point now = PerfClock::now();
    if (++frames_ && std::chrono::duration<double>(now - fps_start_).count() >= 0.5) {
        g_fps = float(frames_ / std::chrono::duration<double>(now - fps_start_).count());
        game::g_fps = g_fps.load();
        frames_ = 0;
        fps_start_ = now;
    }
    // D3DDevice_Swap owns Madagascar's frame policy. At 60 FPS and above it emits repeated completed pictures while
    // holding the game's simulation at 30 Hz; applying app::fps_cap again after every repeated GLES present would
    // turn a 45/60 Hz presentation request into an accidental slow-motion simulation. An explicit renderer limit is
    // still available through SetFrameLimit for code that truly needs one.
    uint32_t limit = g_frame_limit.load();
    if (!limit) {
        next_ = {};
        dynres::Frame();
        return;
    }
    const auto period = std::chrono::duration_cast<PerfClock::duration>(std::chrono::nanoseconds(1000000000ull / limit));
    if (next_ == PerfClock::time_point{} || now > next_ + period) next_ = now;
    next_ += period;
    if (next_ > now) std::this_thread::sleep_until(next_);
    dynres::Frame();
}

}  // namespace host

}  // namespace gpu
