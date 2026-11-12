// Madagascar: what src/common needs from the game, plus game-specific hooks
#include <chrono>
#include <string>

#include "app/app.h"
#include "common/options.h"
#include "core/log.h"
#include "kernel/kernel.h"
#include "xdk/hle.h"

using namespace kernel;

namespace game {

bool HasLanguages() { return false; }
bool LanguageAvailable(const std::string& code) { return code == "int"; }
bool HudShown() { return true; }

}  // namespace game

// 0x000D772E: RenderWare Xbox millisecond timer (RwOsGetTime / RwXboxGetTime).
// In default.xbe this reads [0x00200504] -> [0x8000009C] (Xbox KeTickCount).
// Without advancing time, busy-waits (e.g. sub_0005364F 500ms delay and sub_0005348B 10ms ticks) spin forever.
X86_FUNC(sub_000D772E) {
    static const auto start = std::chrono::steady_clock::now();
    static uint32_t call_count = 0;
    static uint32_t last_sec = 0;
    auto now = std::chrono::steady_clock::now();
    uint32_t ms = static_cast<uint32_t>(std::chrono::duration_cast<std::chrono::milliseconds>(now - start).count());
    W32(0x8000009c, ms);
    call_count++;
    uint32_t sec = ms / 1000;
    if (sec != last_sec) {
        last_sec = sec;
        LOGI("RwOsGetTime: %u ms (calls=%u)", ms, call_count);
    }
    Return(ctx, ms, 0);
}

// Hook main game frame tick (0x0004CDC9)
extern "C" void __imp__sub_0004CDC9(X86Context& ctx, uint8_t* base);
X86_FUNC(sub_0004CDC9) {
    static uint32_t ticks = 0;
    ticks++;
    if (ticks <= 10 || ticks % 60 == 0) {
        LOGI("game: frame tick %u", ticks);
    }
    __imp__sub_0004CDC9(ctx, base);
}

// RenderWare / Toys for Bob audio streaming lock recursion handler.
// On original Xbox, NtReadFile on streaming audio buffers returned STATUS_PENDING,
// and completion callbacks ran asynchronously on a completion thread.
// In XboxXBE-Recomp, NtReadFile completes synchronously. When sub_000F0720 initiates
// an audio buffer read (holding semaphore 0x0027E460), the completion handler
// sub_000E39F0 synchronously re-enters sub_000F0720, which tries to acquire 0x0027E460
// again with an infinite timeout. Because Win32/Xbox semaphores are non-recursive,
// this causes a self-deadlock on the main thread.
// We handle 0x0027E460 as a recursive lock per-thread.
static thread_local int t_stream_sem_depth = 0;
static thread_local uint32_t t_last_stream_sem = 0;

// Hook guest synchronization wait helper (0x000D7FB5 -> NtWaitForSingleObjectEx)
extern "C" void __imp__sub_000D7FB5(X86Context& ctx, uint8_t* base);
X86_FUNC(sub_000D7FB5) {
    static uint32_t wait_count = 0;
    wait_count++;
    uint32_t handle = X86_MEM32(ctx.esp + 4);
    uint32_t timeout = X86_MEM32(ctx.esp + 8);
    uint32_t ret = X86_MEM32(ctx.esp);

    uint32_t stream_sem = X86_MEM32(0x0027E460);
    if (stream_sem != 0 && handle == stream_sem) {
        if (stream_sem != t_last_stream_sem) {
            t_last_stream_sem = stream_sem;
            t_stream_sem_depth = 0;
        }
        if (t_stream_sem_depth > 0) {
            t_stream_sem_depth++;
            static uint32_t reentrant = 0;
            if (++reentrant <= 3 || reentrant % 1000 == 0) {
                LOGI("guest wait #%u: re-entrant acquire on stream semaphore 0x27E460 (handle=%08X, depth=%d) from ret %08X, bypassing wait (x%u)",
                     wait_count, handle, t_stream_sem_depth, ret, reentrant);
            }
            Return(ctx, 0, 2);  // 0 = STATUS_SUCCESS, pops 2 arguments (ret 0x08)
            return;
        }
    }

    // the game waits dozens of times a second (stream locks, worker events): every logged line is flushed to the
    // card on the Switch and costs milliseconds, so only the first few are written
    if (timeout == 0xFFFFFFFF) {
        if (wait_count <= 20) LOGI("guest wait INFINITE: handle %08X from ret %08X (wait #%u)", handle, ret, wait_count);
    } else if (wait_count <= 20) {
        LOGI("guest wait #%u: handle %08X, timeout %08X, ret %08X", wait_count, handle, timeout, ret);
    }

    __imp__sub_000D7FB5(ctx, base);

    // If this thread just successfully acquired stream_sem, set recursion depth to 1
    if (stream_sem != 0 && handle == stream_sem && ctx.eax == 0) {
        t_last_stream_sem = stream_sem;
        t_stream_sem_depth = 1;
    }
}

// Hook guest semaphore release helper (0x000D7E01 -> NtReleaseSemaphore)
extern "C" void __imp__sub_000D7E01(X86Context& ctx, uint8_t* base);
X86_FUNC(sub_000D7E01) {
    uint32_t handle = X86_MEM32(ctx.esp + 4);
    uint32_t stream_sem = X86_MEM32(0x0027E460);

    if (stream_sem != 0 && handle == stream_sem && t_stream_sem_depth > 0) {
        t_stream_sem_depth--;
        if (t_stream_sem_depth > 0) {
            // Still held by outer caller on this thread; bypass kernel release
            uint32_t prev_count_ptr = X86_MEM32(ctx.esp + 12);
            if (prev_count_ptr) {
                X86_MEM32(prev_count_ptr) = 0;
            }
            Return(ctx, 1, 3);  // 1 = TRUE (success), pops 3 arguments (ret 0x0C)
            return;
        }
        // t_stream_sem_depth reached 0: proceed to release kernel semaphore
    }

    __imp__sub_000D7E01(ctx, base);
}

// Hook audio streaming thread init (0x0013C3E0)
extern "C" void __imp__sub_0013C3E0(X86Context& ctx, uint8_t* base);
X86_FUNC(sub_0013C3E0) {
    LOGI("sub_0013C3E0 entry: [0x284E58]=%u", X86_MEM32(0x284E58));
    __imp__sub_0013C3E0(ctx, base);
    LOGI("sub_0013C3E0 exit: eax=%u, [0x284E58]=%u", ctx.eax, X86_MEM32(0x284E58));
}

// RenderWare chunk search step (0x000F0B90, eax = status, ecx = search, ebx = event): reads a 12 byte chunk header
// into [ecx+0x50] and, while its type is not [ecx+0x4C], seeks past the chunk body and reads the next header.
// Logged sparsely to see whether a long search moves through the file or keeps reading the same header.
extern "C" void __imp__sub_000F0B90(X86Context& ctx, uint8_t* base);
X86_FUNC(sub_000F0B90) {
    static uint32_t steps = 0;
    steps++;
    uint32_t search = ctx.ecx;
    uint32_t header = X86_MEM32(search + 0x50);
    uint32_t file = X86_MEM32(X86_MEM32(search) + 0x0C);
    if (steps <= 20 || steps % 500 == 0) {
        LOGI("chunk search #%u: status %u, want %08X, header %08X size %08X ver %08X, file pos %08X, seeking %u",
             steps, ctx.eax, X86_MEM32(search + 0x4C), X86_MEM32(header), X86_MEM32(header + 4), X86_MEM32(header + 8),
             X86_MEM32(file + 0x08), X86_MEM32(search + 0x54));
    }
    __imp__sub_000F0B90(ctx, base);
}

// Async file completion (0x000E39F0, arg = file). For a read (op [file+0x28] = 5) it reports [file+0x2C] as the
// byte count, which only the poll (0x000E34E0, after GetOverlappedResult) writes. On the console a DVD read is always
// pending, but here ReadFile completes at once and 0x000E30E0 calls this from 0x000E31FE without storing the count:
// the stream sees a stale count, drops the data and a chunk search rereads the same empty header forever.
// The count is still on the stack there, as the second argument of the SetFilePointer(count, FILE_CURRENT) just made.
extern "C" void __imp__sub_000E39F0(X86Context& ctx, uint8_t* base);
X86_FUNC(sub_000E39F0) {
    uint32_t ret = X86_MEM32(ctx.esp);
    uint32_t file = X86_MEM32(ctx.esp + 4);
    if (ret == 0x000E3203 && file && X86_MEM32(file + 0x28) == 5) {
        uint32_t count = X86_MEM32(ctx.esp + 0x0C);
        static uint32_t fixes = 0;
        if (++fixes <= 10 || fixes % 1000 == 0) {
            LOGI("read completed at once: file %08X count %u (stale %u), x%u", file, count, X86_MEM32(file + 0x2C), fixes);
        }
        X86_MEM32(file + 0x2C) = count;
    }
    __imp__sub_000E39F0(ctx, base);
}
