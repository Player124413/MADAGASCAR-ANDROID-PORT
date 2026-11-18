#include <algorithm>
#include <iterator>

#include <toml++/toml.hpp>

#include "app/app.h"
#include "audio/output.h"
#include "common/menu.h"
#include "common/mouse_keyboard.h"
#include "common/options.h"

namespace game {

namespace {

// port 0 has the touch controls, the keyboard and mouse; the game sees no input while the panel is open
void Pad(int port, input::Pad& pad) {
    input::SeenPad(pad);
    if (port == 0) {
        pad.connected = true;
#ifndef __SWITCH__
        // L3 + R3 opens and closes the panel (on the Switch host-switch/ui.cpp already does it)
        constexpr uint16_t kCombo = 1u << 6 | 1u << 7;  // left thumb, right thumb
        static bool held = false;
        bool down = (pad.buttons & kCombo) == kCombo;
        if (down && !held) app::ToggleMenu();
        held = down;
#endif
    }
    if (app::MenuOpen()) pad = input::Pad{.connected = pad.connected};
    else if (port == 0) input::Keyboard(pad);
}

// no vibration while the keyboard and mouse are in use
void Rumble(int port, uint16_t& left, uint16_t& right) {
    if (port != 0 || !input::KeyboardActive()) return;
    left = 0;
    right = 0;
}

// what the options list cannot hold
void Load(const void* table) {
    const toml::table& t = *static_cast<const toml::table*>(table);
    Options& o = Opt();
    o.mouse_sensitivity = float(t["mouse_sensitivity"].value_or(double(o.mouse_sensitivity)));
    o.touch_sensitivity = float(t["touch_sensitivity"].value_or(double(o.mouse_sensitivity)));
    o.left_sensitivity = float(t["left_stick_sensitivity"].value_or(double(o.left_sensitivity)));
    o.right_sensitivity = float(t["right_stick_sensitivity"].value_or(double(o.right_sensitivity)));
    o.aspect_ratio = std::min(o.aspect_ratio, 1u);
    std::string language = t["language"].value_or(o.language);
    bool known = std::find(std::begin(kLanguages), std::end(kLanguages), language) != std::end(kLanguages);
    if (known) o.language = language;
    if (const toml::table* keys = t["keys"].as_table())
        for (auto&& [id, value] : *keys)
            if (auto v = value.value<std::string>()) o.keys[std::string(id.str())] = *v;
#ifdef MADAGASCAR_ANDROID
    // Settings are user-editable TOML. Keep malformed or values from an older build in the ranges that the
    // Android renderer and its dynamic-resolution state machine can safely use.
    auto grid = [](uint32_t value) { return std::clamp(value, 20u, 200u) / 5u * 5u; };
    o.anti_aliasing = std::min(o.anti_aliasing, 2u);
    o.dynamic_target_fps = std::clamp(o.dynamic_target_fps, 20u, 60u);
    o.dynamic_min_percent = grid(o.dynamic_min_percent);
    o.dynamic_max_percent = grid(o.dynamic_max_percent);
    if (o.dynamic_min_percent > o.dynamic_max_percent) std::swap(o.dynamic_min_percent, o.dynamic_max_percent);
    o.dynamic_hold = std::clamp(o.dynamic_hold, 1u, 30u);
#endif
}

// after the settings load, before boot
void Init() {
    BootLanguage();  // latched before the options can change the setting
    input::LoadBindings();
    audio::SetOutputMode(Opt().audio_output);
}

void Save(void* table) {
    toml::table& t = *static_cast<toml::table*>(table);
    const Options& o = Opt();
    t.insert("mouse_sensitivity", double(o.mouse_sensitivity));
    t.insert("touch_sensitivity", double(o.touch_sensitivity));
    t.insert("left_stick_sensitivity", double(o.left_sensitivity));
    t.insert("right_stick_sensitivity", double(o.right_sensitivity));
    t.insert("language", o.language);
    toml::table keys;
    for (const auto& [id, value] : o.keys) keys.insert(id, value);
    t.insert("keys", keys);
}

}  // namespace

Options& Opt() {
    static Options o;
    return o;
}

const std::string& BootLanguage() {
    static const std::string language = Opt().language;
    return language;
}

}  // namespace game

const app::GameInfo app::kGame = {
    .title = L"Madagascar",
    .short_title = L"Madagascar",
    .name = L"Madagascar",
    .log = "madagascar",
    .settings = "madagascar.toml",
    .splash = nullptr,
    .missing = L"Put the game in the madagascar folder: its disc image (.iso), or default.xbe and the disc folders beside it.",
    .images = {{"default.xbe", "Madagascar"}},
    .options = {{"fps_counter", &game::Opt().fps_counter, nullptr},
                {"screen_filter", nullptr, &game::Opt().screen_filter},
                {"aspect_ratio", nullptr, &game::Opt().aspect_ratio},
#ifdef MADAGASCAR_ANDROID
                {"bloom", &game::Opt().bloom, nullptr},
                {"fog", &game::Opt().fog, nullptr},
                {"anti_aliasing", nullptr, &game::Opt().anti_aliasing},
                {"hdr", &game::Opt().hdr, nullptr},
                {"dynamic_resolution", &game::Opt().dynamic_resolution, nullptr},
                {"dynamic_target_fps", nullptr, &game::Opt().dynamic_target_fps},
                {"dynamic_min_percent", nullptr, &game::Opt().dynamic_min_percent},
                {"dynamic_max_percent", nullptr, &game::Opt().dynamic_max_percent},
                {"dynamic_hold", nullptr, &game::Opt().dynamic_hold},
#endif
                {"brightness", nullptr, &game::Opt().brightness},
                {"oled_screen", nullptr, &game::Opt().oled},
                {"left_stick_deadzone", nullptr, &game::Opt().left_deadzone},
                {"right_stick_deadzone", nullptr, &game::Opt().right_deadzone},
                {"audio_output", nullptr, &game::Opt().audio_output}},
    .load = game::Load,
    .save = game::Save,
    .init = game::Init,
    .loaded = nullptr,
    .window = nullptr,
    .message = nullptr,
    .ui = app::DrawUi,
    .pad = game::Pad,
    .rumble = game::Rumble,
};
