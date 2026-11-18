// Runtime selection for Android's two GPU backends. Vulkan is the normal path; OpenGL ES 3.2 is an explicit choice
// and the automatic fallback when Vulkan cannot create a renderer on a device.
#include <algorithm>
#include <atomic>
#include <memory>

#include "core/log.h"
#include "gpu/android_platform.h"
#include "gpu/backend.h"

namespace gpu {

std::unique_ptr<Backend> CreateVulkanBackend();
std::unique_ptr<Backend> CreateGlesBackend();
void WaitVulkanGpuFrame(uint64_t n);
void WaitGlesGpuFrame(uint64_t n);

namespace {

std::atomic<int> g_choice{kRendererAuto};
std::atomic<int> g_active{kRendererAuto};

class Chooser final : public Backend {
public:
    bool Init(uint32_t width, uint32_t height) override {
        const int choice = g_choice.load();
        if (choice != kRendererGles) {
            auto vulkan = CreateVulkanBackend();
            if (vulkan->Init(width, height)) {
                LOGI("renderer: Vulkan selected");
                inner_ = std::move(vulkan);
                g_active = kRendererVulkan;
                return true;
            }
            if (choice == kRendererVulkan) {
                LOGE("renderer: Vulkan was selected explicitly and could not initialize");
                return false;
            }
            // Destroy the incomplete backend before EGL claims the Android window.
            vulkan.reset();
            LOGI("renderer: Vulkan unavailable; trying OpenGL ES 3.2 fallback");
        }
        auto gles = CreateGlesBackend();
        if (!gles->Init(width, height)) {
            LOGE("renderer: OpenGL ES 3.2 could not initialize");
            return false;
        }
        LOGI("renderer: OpenGL ES 3.2 selected");
        inner_ = std::move(gles);
        g_active = kRendererGles;
        return true;
    }

    void Clear(const State& state, uint32_t count, const uint8_t* rects, uint32_t flags, uint32_t color, float depth,
               uint32_t stencil) override {
        inner_->Clear(state, count, rects, flags, color, depth, stencil);
    }
    void Draw(const State& state, uint32_t primitive, uint32_t start, uint32_t count, const uint8_t* indices) override {
        inner_->Draw(state, primitive, start, count, indices);
    }
    void Copy(const Surface& source, const Surface& destination, uint32_t count, uint32_t sourceRects,
              uint32_t destinationPoints) override {
        inner_->Copy(source, destination, count, sourceRects, destinationPoints);
    }
    void Present(const Surface& front) override { inner_->Present(front); }
    void Sync(uint32_t address, uint32_t size) override { inner_->Sync(address, size); }
    void SyncLate(uint32_t address) override { inner_->SyncLate(address); }
    bool DeliverLate(uint32_t address) override { return inner_->DeliverLate(address); }
    void RecordLate(uint32_t address) override { inner_->RecordLate(address); }
    void Flush() override { inner_->Flush(); }
    void SetGamma(const uint8_t* ramp) override { inner_->SetGamma(ramp); }
    void SetOverlay(const uint8_t* bgra, uint32_t width, uint32_t height, const int32_t* rect) override {
        inner_->SetOverlay(bgra, width, height, rect);
    }
    void ClearReport() override { inner_->ClearReport(); }
    void GetReport(uint32_t report) override { inner_->GetReport(report); }
    uint32_t Fence() override { return inner_->Fence(); }
    bool FencePending(uint32_t fence) override { return inner_->FencePending(fence); }
    void WaitFence(uint32_t fence) override { inner_->WaitFence(fence); }
    bool Busy(uint32_t resource) override { return inner_->Busy(resource); }
    void WaitResource(uint32_t resource) override { inner_->WaitResource(resource); }

private:
    std::unique_ptr<Backend> inner_;
};

}  // namespace

std::unique_ptr<Backend> CreateBackend() { return std::make_unique<Chooser>(); }

void SetRendererChoice(int renderer) { g_choice = std::clamp(renderer, int(kRendererAuto), int(kRendererGles)); }
int GetActiveRenderer() { return g_active.load(); }
void SetRendererMarker(const char*) {}

// Madagascar's D3D implementation waits one frame behind the renderer. Both backend implementations have their own
// native fence ownership, so dispatch this legacy free function after the chooser selected one.
void WaitGpuFrame(uint64_t n) {
    if (g_active == kRendererVulkan) WaitVulkanGpuFrame(n);
    else if (g_active == kRendererGles) WaitGlesGpuFrame(n);
}

}  // namespace gpu
