# Madagascar for Android

This directory builds an **ARM64 Android Vulkan host** for the Madagascar static recompilation.  It deliberately contains neither the Xbox game files nor generated output from them.

> **Status — implementation preview.** The Android launcher, native SDL3/Vulkan host, import flow, touch editor and compatibility paths are in source control. This checkout has no Android SDK/NDK, Java, game XBE, or recompiler output, so an APK and physical-device run have **not** been built or verified here. In particular, Mali behaviour still needs device testing before it can be claimed artifact-free.

## What is included

- A two-stage app: a scoped-storage launcher followed by the full-screen SDL3 game activity.
- Safe import of a legal Xbox ISO (`.part` copy then atomic replacement) or of an extracted folder containing `default.xbe` (staging directory then replacement). Existing files remain untouched if an import fails or is cancelled.
- An Android SDL3/Vulkan renderer based on the permitted Black-Android reference, adapted to the Madagascar source tree:
  - Vulkan 1.1 minimum with render-pass fallback if dynamic rendering is unavailable;
  - fence fallback rather than timeline semaphores on Vulkan 1.1 devices (including the documented Mali-G76 failure case);
  - CPU DXT1/3/5 decode if BC texture compression is unavailable;
  - shader specialization for opaque texture/sampler parameters, avoiding the malformed SPIR-V path that causes corruption on affected Mali drivers;
  - surface/swapchain recreation, bounded acquire waits, shader and pipeline caches, texture retirement, and 16 KiB ELF page alignment;
  - dynamic internal resolution with a conservative 30 FPS default.
- Editable touch controls: three placement presets, opacity and global size, button style, floating/fixed analog stick, gyroscope aiming, and an in-game drag/resize/hide editor persisted in `layout.toml`.
- Gamepad, mouse/keyboard, virtual-controller and controller-gyro routing.

The imported reference code is covered by [`LICENSE-Black-Android`](LICENSE-Black-Android). See the root project notices for the remaining licenses.

## Prerequisites

1. A legal copy of **Madagascar for original Xbox**, specifically the XBE revision from which this port was recompiled. The native host compares its SHA-1 to the XBE embedded at build time and refuses mismatched revisions.
2. Android Studio / command-line SDK with:
   - Android SDK Platform 34;
   - NDK `28.2.13676358`;
   - CMake `3.31.6`;
   - JDK 17.
3. A host recompiler build and the resulting Madagascar generated sources. Follow the existing kit documentation, then make sure these untracked paths exist:

   ```text
   kit/exemplos/madagascar/assets/default.xbe
   kit/exemplos/madagascar/generated/game/x86_func_mapping.cpp
   kit/exemplos/madagascar/generated/game/x86_recomp.0.cpp
   # ...every partition listed in kit/exemplos/madagascar/config/game.toml
   ```

The XBE and generated output are private game-derived files and must not be committed or distributed.

## Build

From this directory:

```bash
./gradlew :app:assembleDebug
```

The first Gradle build fetches SDL `release-3.4.16` into `android/third_party/SDL`; CMake fetches glslang, toml++, and Dear ImGui. An internet connection to those upstream repositories is needed for that first configure.

For generated files outside the default kit locations, pass absolute paths to Gradle:

```bash
./gradlew :app:assembleDebug \
  -PxbeGenerated=/absolute/path/to/generated/game \
  -PxbeImage=/absolute/path/to/default.xbe
```

The debug build intentionally compiles native code in `Release` mode because unoptimized translated x86 code is not playable. The APK is normally written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### CI smoke test

[`.github/workflows/android-apk.yml`](../.github/workflows/android-apk.yml) installs the pinned JDK/SDK/NDK/CMake versions and runs:

```bash
./gradlew :app:assembleDebug -PandroidStub
```

`-PandroidStub` selects an intentionally tiny `libmain.so`, rather than the Xbox-derived generated sources. The workflow then verifies that the APK contains only `arm64-v8a` native libraries (`libSDL3.so` and `libmain.so`) and no XBE/ISO/XISO files, then uploads it as a seven-day CI artifact. This is a reproducibility and packaging test, **not a playable build or game test**.

For a real APK, do **not** pass `-PandroidStub`; first provide the legal XBE and every generated partition listed above. A real build is expected to fail early with an explicit missing-input message if they are absent. Run the stub job from the GitHub Actions tab after changing Gradle/CMake/manifest code, then build and exercise the real APK locally or in a private CI environment where the game-derived inputs are available.

## First run and controls

1. Install the APK. The launcher requests no broad storage permission.
2. Choose **Import Xbox ISO** or **Import folder with default.xbe**. The selected content is copied into the application’s scoped external-files directory, so the game can read it after the document picker closes.
3. Tap **Launch game** after the launcher reports that the files are ready.
4. In game, use the on-screen gear/settings control. The **Touch Controls** page has layouts, opacity, size, button style, analog-stick mode and the layout editor. The graphics page has dynamic resolution, render scale bounds, fog/bloom and anti-aliasing controls.

The app uses a landscape, immersive activity and keeps the screen awake while gameplay is active. Back/recents will pause rendering/audio through the SDL lifecycle and recreate the Vulkan surface as necessary.

## Mali validation plan

The source contains conservative compatibility work, but it is not proof of compatibility. Before a release, test a clean install and at least one gameplay session on a Vulkan 1.1+ Mali device (for example G76/G77/G78), checking:

1. boot/import and a resume from background;
2. cutscenes, HUD, fog, bloom, translucent effects and rapid scene changes for corruption/flicker;
3. at least ten minutes of gameplay while monitoring thermal throttling and FPS;
4. low-memory recovery and an orientation/surface recreation;
5. a saved custom touch layout, Bluetooth controller, and gyro if available.

Attach `Android/data/com.player124413.madagascar/files/logs/` to any report, along with device model, Android version, Vulkan driver and graphics settings. Do not report Mali as verified until those runs are complete.
