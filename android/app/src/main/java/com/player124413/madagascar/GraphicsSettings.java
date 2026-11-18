package com.player124413.madagascar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Launcher-side editor for the graphics keys read by android/native/main.cpp and game_info.cpp. */
final class GraphicsSettings {
    static final String FILE_NAME = "madagascar.toml";
    // Must match android/native/main.cpp. Version 5 migrates legacy stretch/4:3 choices to the standard 16:9 view.
    private static final int DEFAULTS_VERSION = 5;

    static final int[] RENDER_SCALES = {50, 67, 75, 85, 100};
    // 0 is the persisted/native unlimited value. The game keeps its original 30 Hz simulation at every high
    // presentation rate, so this does not speed up gameplay, timers, physics or cutscenes.
    static final int[] FPS_CAPS = {30, 45, 60, 90, 120, 0};
    static final int[] ANISOTROPY = {0, 2, 4, 8, 16};
    static final int[] DYNAMIC_TARGETS = {30, 45, 60};
    static final int[] DYNAMIC_MIN = {35, 45, 55, 67};
    static final int[] DYNAMIC_MAX = {67, 75, 85, 100};
    static final int[] DYNAMIC_HOLD = {2, 5, 10, 15};
    static final int[] BRIGHTNESS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

    static final class Config {
        // 0 is standard 16:9; 1 is the 21:9 ultrawide presentation.
        int aspectRatio;
        int renderPercent = 100;
        int fpsCap = 30;
        boolean vsync = true;
        int anisotropicFiltering = 0;
        int screenFilter;
        int brightness = 5;
        int oledScreen;
        boolean bloom = true;
        boolean fog = true;
        int antiAliasing;
        boolean hdr;
        // 0 Auto (Vulkan then GLES fallback), 1 Vulkan only, 2 OpenGL ES 3.2 only.
        int renderer;
        boolean dynamicResolution;
        int dynamicTargetFps = 30;
        int dynamicMinPercent = 45;
        int dynamicMaxPercent = 100;
        int dynamicHold = 5;

        void normalize() {
            aspectRatio = clamp(aspectRatio, 0, 1);
            renderPercent = clamp(renderPercent, 20, 200);
            fpsCap = clamp(fpsCap, 0, 240);
            anisotropicFiltering = in(ANISOTROPY, anisotropicFiltering) ? anisotropicFiltering : 0;
            screenFilter = clamp(screenFilter, 0, 8);
            brightness = clamp(brightness, 0, 10);
            oledScreen = clamp(oledScreen, 0, 2);
            antiAliasing = clamp(antiAliasing, 0, 2);
            renderer = clamp(renderer, 0, 2);
            dynamicTargetFps = clamp(dynamicTargetFps, 20, 60);
            dynamicMinPercent = snap(clamp(dynamicMinPercent, 20, 200));
            dynamicMaxPercent = snap(clamp(dynamicMaxPercent, 20, 200));
            if (dynamicMinPercent > dynamicMaxPercent) dynamicMaxPercent = dynamicMinPercent;
            dynamicHold = clamp(dynamicHold, 1, 30);
        }
    }

    private GraphicsSettings() {}

    static Config load(File directory) {
        Config config = new Config();
        File file = new File(directory, FILE_NAME);
        if (!file.isFile()) return config;
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            int defaults = integer(text, "defaults", 0);
            config.aspectRatio = integer(text, "aspect_ratio", config.aspectRatio);
            config.renderPercent = integer(text, "render_percent", config.renderPercent);
            config.fpsCap = integer(text, "fps_cap", config.fpsCap);
            config.vsync = bool(text, "vsync", config.vsync);
            config.anisotropicFiltering = integer(text, "anisotropic_filtering", config.anisotropicFiltering);
            config.screenFilter = integer(text, "screen_filter", config.screenFilter);
            config.brightness = integer(text, "brightness", config.brightness);
            config.oledScreen = integer(text, "oled_screen", config.oledScreen);
            config.bloom = bool(text, "bloom", config.bloom);
            config.fog = bool(text, "fog", config.fog);
            config.antiAliasing = integer(text, "anti_aliasing", config.antiAliasing);
            config.hdr = bool(text, "hdr", config.hdr);
            config.renderer = integer(text, "renderer", config.renderer);
            config.dynamicResolution = bool(text, "dynamic_resolution", config.dynamicResolution);
            config.dynamicTargetFps = integer(text, "dynamic_target_fps", config.dynamicTargetFps);
            config.dynamicMinPercent = integer(text, "dynamic_min_percent", config.dynamicMinPercent);
            config.dynamicMaxPercent = integer(text, "dynamic_max_percent", config.dynamicMaxPercent);
            config.dynamicHold = integer(text, "dynamic_hold", config.dynamicHold);
            if (defaults < 4) {
                // The old first-run choice rendered at 67% and then enlarged it, which looks like a blur on a phone.
                config.renderPercent = 100;
                config.dynamicResolution = false;
                config.antiAliasing = 0;
            }
            if (defaults < DEFAULTS_VERSION) {
                // v4 used stretch and original 4:3. The launcher now exposes only its two Android-wide formats.
                config.aspectRatio = 0;
            }
        } catch (Exception ignored) {
            // The native host also uses conservative defaults when a settings file cannot be parsed.
        }
        config.normalize();
        return config;
    }

    static void save(File directory, Config config) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create the app files directory.");
        config.normalize();
        File file = new File(directory, FILE_NAME);
        String text = file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        // Keep native migration in sync with the launcher: v4 is the full-resolution, no-auto-blur default.
        values.put("defaults", Integer.toString(DEFAULTS_VERSION));
        values.put("aspect_ratio", Integer.toString(config.aspectRatio));
        values.put("render_percent", Integer.toString(config.renderPercent));
        values.put("fps_cap", Integer.toString(config.fpsCap));
        values.put("vsync", Boolean.toString(config.vsync));
        values.put("anisotropic_filtering", Integer.toString(config.anisotropicFiltering));
        values.put("screen_filter", Integer.toString(config.screenFilter));
        values.put("brightness", Integer.toString(config.brightness));
        values.put("oled_screen", Integer.toString(config.oledScreen));
        values.put("bloom", Boolean.toString(config.bloom));
        values.put("fog", Boolean.toString(config.fog));
        values.put("anti_aliasing", Integer.toString(config.antiAliasing));
        values.put("hdr", Boolean.toString(config.hdr));
        values.put("renderer", Integer.toString(config.renderer));
        values.put("dynamic_resolution", Boolean.toString(config.dynamicResolution));
        values.put("dynamic_target_fps", Integer.toString(config.dynamicTargetFps));
        values.put("dynamic_min_percent", Integer.toString(config.dynamicMinPercent));
        values.put("dynamic_max_percent", Integer.toString(config.dynamicMaxPercent));
        values.put("dynamic_hold", Integer.toString(config.dynamicHold));
        for (Map.Entry<String, String> entry : values.entrySet()) text = replaceOrAppend(text, entry.getKey(), entry.getValue());

        File temporary = new File(directory, FILE_NAME + ".new");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String replaceOrAppend(String source, String key, String value) {
        Pattern pattern = Pattern.compile("(?m)^([\\t ]*" + Pattern.quote(key) + "[\\t ]*=[\\t ]*)([^#\\r\\n]*)(.*)$");
        Matcher matcher = pattern.matcher(source);
        if (matcher.find()) return matcher.replaceFirst("$1" + Matcher.quoteReplacement(value) + "$3");
        String separator = source.isEmpty() || source.endsWith("\n") ? "" : "\n";
        return source + separator + key + " = " + value + "\n";
    }

    private static int integer(String text, String key, int fallback) {
        String value = raw(text, key);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean bool(String text, String key, boolean fallback) {
        String value = raw(text, key);
        if (value == null) return fallback;
        return "true".equalsIgnoreCase(value.trim()) ? true : "false".equalsIgnoreCase(value.trim()) ? false : fallback;
    }

    private static String raw(String text, String key) {
        Matcher matcher = Pattern.compile("(?m)^[\\t ]*" + Pattern.quote(key) + "[\\t ]*=[\\t ]*([^#\\r\\n]+)").matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static int snap(int value) {
        return value / 5 * 5;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean in(int[] values, int value) {
        for (int item : values) if (item == value) return true;
        return false;
    }
}
