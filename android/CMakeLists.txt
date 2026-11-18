# Madagascar for Android: Xbox x86 is statically recompiled on the PC, then linked with
# the shared Xbox runtime, the Madagascar D3D8/audio layer, and the SDL3/Vulkan Android host.
cmake_minimum_required(VERSION 3.22)
project(madagascar_android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

get_filename_component(KIT "${CMAKE_CURRENT_SOURCE_DIR}/../kit" ABSOLUTE)
set(GAME "${KIT}/exemplos/madagascar")
set(NATIVE "${CMAKE_CURRENT_SOURCE_DIR}/native")
set(SDK "${KIT}/sdk/XboxXBE-Recomp")
set(THIRD "${CMAKE_CURRENT_SOURCE_DIR}/third_party")

# CI builds this small Android library to validate Gradle, NDK, SDL and APK packaging without distributing
# an XBE or source derived from it. It is intentionally opt-in and cannot make a playable APK.
option(MADAGASCAR_ANDROID_STUB "Build the packaging-only Android stub instead of the game" OFF)

if(NOT MADAGASCAR_ANDROID_STUB)
    # Recompiler output and input XBE are user-provided (they must never be shipped by this repo).
    set(XBE_GENERATED "${GAME}/generated/game" CACHE PATH "XboxXBE-Recomp output for Madagascar")
    set(XBE_IMAGE "${GAME}/assets/default.xbe" CACHE FILEPATH "The user's Madagascar Xbox default.xbe")
    if(NOT EXISTS "${XBE_GENERATED}/x86_func_mapping.cpp" OR NOT EXISTS "${XBE_IMAGE}")
        message(FATAL_ERROR
            "Madagascar's recompiled code is missing. Put your own default.xbe in ${GAME}/assets and run the recompile step in kit/README.md first.")
    endif()

    file(STRINGS "${GAME}/config/game.toml" GAME_PARTITIONS REGEX "^partitions *=")
    string(REGEX MATCH "[0-9]+" GAME_PARTITIONS "${GAME_PARTITIONS}")
    if(NOT GAME_PARTITIONS)
        message(FATAL_ERROR "Could not read partitions from ${GAME}/config/game.toml")
    endif()
    math(EXPR LAST_PARTITION "${GAME_PARTITIONS} - 1")
    set(GEN_SOURCES "${XBE_GENERATED}/x86_func_mapping.cpp")
    foreach(i RANGE ${LAST_PARTITION})
        set(PARTITION_SOURCE "${XBE_GENERATED}/x86_recomp.${i}.cpp")
        if(NOT EXISTS "${PARTITION_SOURCE}")
            message(FATAL_ERROR "Missing generated partition ${PARTITION_SOURCE}; rerun the Madagascar recompile step.")
        endif()
        list(APPEND GEN_SOURCES "${PARTITION_SOURCE}")
    endforeach()
    file(SHA1 "${XBE_IMAGE}" XBE_SHA1)
    file(CONFIGURE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/image_sha1.cpp"
         CONTENT "extern const char kImageSha1[] = \"${XBE_SHA1}\";\n")

    set(BUILD_COMMIT "unknown")
    find_package(Git QUIET)
    if(GIT_EXECUTABLE)
        execute_process(COMMAND "${GIT_EXECUTABLE}" rev-parse --short HEAD
                        WORKING_DIRECTORY "${KIT}"
                        OUTPUT_VARIABLE BUILD_COMMIT OUTPUT_STRIP_TRAILING_WHITESPACE
                        ERROR_QUIET RESULT_VARIABLE GIT_RESULT)
        if(NOT GIT_RESULT AND BUILD_COMMIT)
            # use the tracked source revision, not the XBE-derived generated code
            set(BUILD_COMMIT "${BUILD_COMMIT}")
        else()
            set(BUILD_COMMIT "unknown")
        endif()
    endif()
    file(CONFIGURE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/build_info.h"
         CONTENT "#define MADAGASCAR_COMMIT \"${BUILD_COMMIT}\"\n#define MADAGASCAR_ANDROID_VERSION \"0.1.0-dev\"\n")
endif()

# SDL's Android Java activity and C library come from the exact same release tag.
set(SDL_DIR "${THIRD}/SDL")
if(NOT EXISTS "${SDL_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "SDL3 is missing at ${SDL_DIR}; run ./gradlew :app:fetchSDL (or build from Android Studio) first.")
endif()
set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST_LIBRARY OFF CACHE BOOL "" FORCE)
set(SDL_RENDER OFF CACHE BOOL "" FORCE)
set(SDL_GPU OFF CACHE BOOL "" FORCE)
set(SDL_CAMERA OFF CACHE BOOL "" FORCE)
add_subdirectory("${SDL_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/SDL" EXCLUDE_FROM_ALL)

if(MADAGASCAR_ANDROID_STUB)
    message(STATUS "Building Madagascar Android packaging stub (no game code or XBE)")
    add_library(main SHARED "${NATIVE}/stub_main.cpp")
    target_link_libraries(main PRIVATE SDL3::SDL3 android log)
    # Match the release library so the APK job also checks 16 KiB device compatibility.
    target_link_options(main PRIVATE -Wl,-z,max-page-size=16384)
    return()
endif()

# Build only glslang's HLSL -> SPIR-V path; shader optimization is deliberately disabled because
# the runtime shader specializer and Mali/Vulkan 1.1 fallback must preserve the game's descriptors.
set(ENABLE_OPT OFF CACHE BOOL "" FORCE)
set(ENABLE_HLSL ON CACHE BOOL "" FORCE)
set(ENABLE_GLSLANG_BINARIES OFF CACHE BOOL "" FORCE)
set(ENABLE_SPVREMAPPER OFF CACHE BOOL "" FORCE)
set(GLSLANG_TESTS OFF CACHE BOOL "" FORCE)
set(GLSLANG_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(BUILD_EXTERNAL OFF CACHE BOOL "" FORCE)
include(FetchContent)
FetchContent_Declare(glslang GIT_REPOSITORY https://github.com/KhronosGroup/glslang.git GIT_TAG 16.0.0 GIT_SHALLOW TRUE)
FetchContent_Declare(tomlplusplus GIT_REPOSITORY https://github.com/marzer/tomlplusplus.git GIT_TAG v3.4.0 GIT_SHALLOW TRUE)
FetchContent_Declare(imgui GIT_REPOSITORY https://github.com/ocornut/imgui.git GIT_TAG v1.91.0 GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(glslang tomlplusplus imgui)

# Reuse the SDK's portable runtime, excluding the Windows app/input/D3D/DirectSound implementations replaced below.
file(GLOB RUNTIME_SOURCES "${SDK}/runtime/*/*.cpp")
list(FILTER RUNTIME_SOURCES EXCLUDE REGEX
     "/runtime/app/|/core/input\\.cpp$|/gpu/(d3d12|render_thread)\\.cpp$|/audio/xaudio2\\.cpp$|/xdk/(dsound|d3d8)\\.cpp$")
file(GLOB GAME_SOURCES CONFIGURE_DEPENDS "${GAME}/src/*/*.cpp")
file(GLOB ANDROID_SOURCES CONFIGURE_DEPENDS "${NATIVE}/*.cpp")
# The stub is selected above only for CI and must never be linked into a playable build alongside SDL_main.
list(FILTER ANDROID_SOURCES EXCLUDE REGEX "/stub_main\\.cpp$")
set(IMGUI_SOURCES
    "${imgui_SOURCE_DIR}/imgui.cpp"
    "${imgui_SOURCE_DIR}/imgui_draw.cpp"
    "${imgui_SOURCE_DIR}/imgui_tables.cpp"
    "${imgui_SOURCE_DIR}/imgui_widgets.cpp"
    "${imgui_SOURCE_DIR}/backends/imgui_impl_vulkan.cpp")

add_library(main SHARED
    ${RUNTIME_SOURCES} ${GAME_SOURCES} ${ANDROID_SOURCES} ${GEN_SOURCES} ${IMGUI_SOURCES}
    "${CMAKE_CURRENT_BINARY_DIR}/image_sha1.cpp")
target_include_directories(main PRIVATE
    "${NATIVE}/compat" "${SDK}/harness/compat" "${NATIVE}" "${GAME}/src"
    "${SDK}/runtime" "${XBE_GENERATED}" "${CMAKE_CURRENT_BINARY_DIR}" "${glslang_SOURCE_DIR}")
target_include_directories(main SYSTEM PRIVATE "${imgui_SOURCE_DIR}")
target_compile_definitions(main PRIVATE MADAGASCAR_ANDROID)
target_compile_options(main PRIVATE -fno-strict-aliasing -fsigned-char)

set(HOST_SOURCES ${RUNTIME_SOURCES} ${GAME_SOURCES} ${ANDROID_SOURCES})
set_source_files_properties(${HOST_SOURCES} PROPERTIES
    COMPILE_OPTIONS "-include;${NATIVE}/compat/crt.h")
set_source_files_properties(${GEN_SOURCES} PROPERTIES
    COMPILE_OPTIONS "-O2;-w;-fno-strict-aliasing;-fsigned-char")
set_source_files_properties("${NATIVE}/vk_shader.cpp" PROPERTIES COMPILE_DEFINITIONS ENABLE_HLSL)
target_link_libraries(main PRIVATE
    SDL3::SDL3 tomlplusplus::tomlplusplus glslang glslang-default-resource-limits
    vulkan android log)

# Android 15+ devices may use 16 KiB pages. Keep the native library loadable there as well.
target_link_options(main PRIVATE -Wl,-z,max-page-size=16384)
