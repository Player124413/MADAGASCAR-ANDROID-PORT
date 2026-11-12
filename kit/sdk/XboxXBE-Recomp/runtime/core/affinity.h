#pragma once
#ifdef __SWITCH__
#include <switch.h>
#endif

namespace core {

// On the Switch a thread made without a core (std::thread, pthread) lands on core 0 with all the others: the game,
// the render thread, audio and streaming then share one core while two sit idle. This one prefers `core` and may
// still move to any of the three a title owns when its own is busy.
inline void PreferCore(int core) {
#ifdef __SWITCH__
    svcSetThreadCoreMask(CUR_THREAD_HANDLE, core, 0x7);
#else
    (void)core;
#endif
}

}  // namespace core
