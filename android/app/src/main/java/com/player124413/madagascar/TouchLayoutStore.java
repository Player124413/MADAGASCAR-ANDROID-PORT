package com.player124413.madagascar;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The launcher-side representation of android/native/touch.cpp's layout.toml.
 * Keep the IDs and the default order in lockstep with that file: the native host reads this file before the first
 * game frame, so launcher changes are already active when GameActivity opens.
 */
final class TouchLayoutStore {
    static final String FILE_NAME = "layout.toml";

    // The native array order: all virtual Xbox inputs, followed by the left stick.
    static final String[] IDS = {
            "fire", "fire_left", "aim", "mode", "melee", "use", "grenade", "reload", "crouch", "weapon",
            "health", "map", "pause", "gyro", "back", "suppressor", "dpad_up", "dpad_down", "dpad_left",
            "dpad_right", "panel", "enter", "zoom", "stick"
    };

    // Labels deliberately identify the Xbox input rather than the old game-specific artwork.
    private static final String[] XBOX_LABELS = {
            "RT", "RT", "R3", "D-PAD ↑", "B", "X", "LT", "A", "L3", "D-PAD →", "D-PAD ↓", "BACK",
            "START", "GYRO", "B", "Y", "D-PAD ↑", "D-PAD ↓", "D-PAD ←", "D-PAD →", "PANEL", "A",
            "D-PAD ↑", "LEFT STICK"
    };

    // Controls exposed in the launcher editor. Gyro and the gear/panel are intentionally absent: they are not Xbox
    // controls and android/native/touch.cpp keeps them hidden as migration-only layout entries.
    private static final int[] EDITABLE = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 15, 22, 23};

    // Android native defaults: the two-finger preset in android/native/touch.cpp.
    private static final float[][] DEFAULTS = {
            {0.924f, 0.66f, 0.095f}, {0.059f, 0.40f, 0.065f}, {0.969f, 0.40f, 0.065f}, {0.91f, 0.40f, 0.045f},
            {0.843f, 0.56f, 0.05f}, {0.789f, 0.40f, 0.055f}, {0.784f, 0.70f, 0.05f}, {0.838f, 0.84f, 0.055f},
            {0.964f, 0.88f, 0.05f}, {0.50f, 0.90f, 0.05f}, {0.559f, 0.90f, 0.045f}, {0.473f, 0.07f, 0.04f},
            {0.527f, 0.07f, 0.04f}, {0.419f, 0.07f, 0.04f}, {0.036f, 0.08f, 0.045f}, {0.70f, 0.56f, 0.042f},
            {0.11f, 0.60f, 0.048f}, {0.11f, 0.80f, 0.048f}, {0.064f, 0.70f, 0.048f}, {0.156f, 0.70f, 0.048f},
            {0.581f, 0.07f, 0.04f}, {0.92f, 0.78f, 0.07f}, {0.715f, 0.40f, 0.05f}, {0.108f, 0.70f, 0.12f}
    };

    static final class Layout {
        final float[] x = new float[IDS.length];
        final float[] y = new float[IDS.length];
        final float[] radius = new float[IDS.length];
        final boolean[] hidden = new boolean[IDS.length];
        String preset = "default";
        float opacity = 0.85f;
        float scale = 1.0f;
        boolean fixedStick;

        Layout() {
            reset();
        }

        void reset() {
            preset = "default";
            opacity = 0.85f;
            scale = 1.0f;
            fixedStick = false;
            for (int i = 0; i < IDS.length; i++) {
                x[i] = DEFAULTS[i][0];
                y[i] = DEFAULTS[i][1];
                radius[i] = DEFAULTS[i][2];
                hidden[i] = false;
            }
        }
    }

    private TouchLayoutStore() {}

    static int[] editableControls() {
        return EDITABLE.clone();
    }

    static String xboxLabel(int index) {
        return index >= 0 && index < XBOX_LABELS.length ? XBOX_LABELS[index] : "XBOX";
    }

    static String controlId(int index) {
        return index >= 0 && index < IDS.length ? IDS[index] : "";
    }

    static Layout load(File directory) {
        Layout layout = new Layout();
        File file = new File(directory, FILE_NAME);
        if (!file.isFile()) return layout;

        int current = -1;
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            for (String raw; (raw = reader.readLine()) != null;) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[controls.") && line.endsWith("]")) {
                    current = indexOf(line.substring("[controls.".length(), line.length() - 1));
                    continue;
                }
                int equals = line.indexOf('=');
                if (equals < 0) continue;
                String key = line.substring(0, equals).trim();
                String value = withoutComment(line.substring(equals + 1)).trim();
                if (current < 0) {
                    if ("preset".equals(key)) layout.preset = unquote(value, layout.preset);
                    else if ("opacity".equals(key)) layout.opacity = range(number(value, layout.opacity), 0.2f, 1.0f);
                    else if ("scale".equals(key)) layout.scale = range(number(value, layout.scale), 0.5f, 1.5f);
                    else if ("stick".equals(key)) layout.fixedStick = "fixed".equals(unquote(value, "floating"));
                    continue;
                }
                if ("x".equals(key)) layout.x[current] = range(number(value, layout.x[current]), 0f, 1f);
                else if ("y".equals(key)) layout.y[current] = range(number(value, layout.y[current]), 0f, 1f);
                else if ("r".equals(key)) layout.radius[current] = range(number(value, layout.radius[current]), 0.02f, 0.25f);
                else if ("hidden".equals(key)) layout.hidden[current] = "true".equalsIgnoreCase(value);
            }
        } catch (Exception ignored) {
            // A malformed or partially written old layout must never prevent the launcher from opening. The native
            // loader follows the same policy and uses defaults for anything it cannot understand.
        }
        return layout;
    }

    static void save(File directory, Layout layout) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create the app files directory.");
        }
        File target = new File(directory, FILE_NAME);
        File temporary = new File(directory, FILE_NAME + ".new");
        try (Writer out = new OutputStreamWriter(new FileOutputStream(temporary), StandardCharsets.UTF_8)) {
            out.write("# Madagascar Android touch layout. Edited by the launcher.\n");
            out.write("preset = \"");
            out.write(layout.preset);
            out.write("\"\n");
            out.write(String.format(Locale.ROOT, "opacity = %.3f%nscale = %.3f%n", layout.opacity, layout.scale));
            out.write("stick = \"");
            out.write(layout.fixedStick ? "fixed" : "floating");
            out.write("\"\n\n");
            for (int i = 0; i < IDS.length; i++) {
                out.write("[controls.");
                out.write(IDS[i]);
                out.write("]\n");
                out.write(String.format(Locale.ROOT, "x = %.5f%ny = %.5f%nr = %.5f%nhidden = %s%n%n",
                        x(layout.x[i]), x(layout.y[i]), x(layout.radius[i]), layout.hidden[i] ? "true" : "false"));
            }
        }
        if (target.exists() && !target.delete()) {
            temporary.delete();
            throw new IOException("Could not replace the previous touch layout.");
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("Could not save the touch layout.");
        }
    }

    private static float x(float value) {
        return range(value, 0f, 1f);
    }

    private static int indexOf(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return -1;
    }

    private static String withoutComment(String value) {
        int comment = value.indexOf('#');
        return comment >= 0 ? value.substring(0, comment) : value;
    }

    private static String unquote(String value, String fallback) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return fallback;
    }

    private static float number(String value, float fallback) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static float range(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
