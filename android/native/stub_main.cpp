// CI-only entry point. It proves that SDL3, the NDK toolchain and APK packaging can produce libmain.so without
// embedding or accessing the proprietary XBE/recompiler output. A stub APK is not a game build.
#include <android/log.h>

extern "C" int SDL_main(int, char**) {
    __android_log_print(ANDROID_LOG_INFO, "MADAGASCAR", "Android packaging stub started; no game data is included");
    return 0;
}
