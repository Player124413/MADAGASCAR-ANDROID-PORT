package com.player124413.madagascar;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/** The standalone launcher and legal game-file setup screen. */
public final class LauncherActivity extends Activity {
    private static final int REQUEST_IMPORT_FOLDER = 0x4D42;

    private File appDirectory;
    private File gameDirectory;
    private TextView statusTitle;
    private TextView statusDetail;
    private TextView pathLabel;
    private Button launchButton;
    private Button isoButton;
    private Button folderButton;
    private GameImporter discImporter;
    private FolderImporter folderImporter;
    private boolean importing;
    // buildLauncher() may fail part way through on an OEM framework. Keep onResume from touching fields created later
    // in that method; the fallback screen must remain visible with the original diagnostic intact.
    private boolean launcherReady;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Window w = getWindow();
            w.setStatusBarColor(Color.rgb(16, 19, 15));
            w.setNavigationBarColor(Color.rgb(16, 19, 15));
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

            File files = getExternalFilesDir(null);
            if (files == null) files = getFilesDir();
            // SDL's app::ExeDir() resolves to this same scoped files directory. Launcher settings are therefore
            // applied by the native host before its first game frame, with no broad-storage permission required.
            appDirectory = files;
            gameDirectory = new File(appDirectory, "game");
            gameDirectory.mkdirs();
            discImporter = new GameImporter(this);
            folderImporter = new FolderImporter(this);

            View launcher = buildLauncher();
            setContentView(launcher);
            launcherReady = true;
            refreshGameState();
            launcher.post(() -> showCreditsIfNeeded(false));
        } catch (Throwable error) {
            // Keep the launch activity alive long enough to show a report location instead of immediately returning
            // to the home screen on an OEM-specific framework/resource failure.
            launcherReady = false;
            MadagascarApplication.writeFailure(this, "Launcher initialization failed", error);
            showStartupFailure(error);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (launcherReady) refreshGameState();
    }

    private void showStartupFailure(Throwable error) {
        TextView view = new TextView(this);
        int padding = dp(24);
        view.setPadding(padding, padding, padding, padding);
        view.setTextColor(Color.WHITE);
        view.setTextSize(16);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setText(getString(R.string.launcher_startup_failed, error.getClass().getSimpleName(),
                error.getMessage() == null ? getString(R.string.launcher_startup_no_message) : error.getMessage()));
        view.setBackgroundColor(Color.rgb(16, 19, 15));
        setContentView(view);
    }

    private View buildLauncher() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {Color.rgb(17, 22, 18), Color.rgb(25, 29, 20), Color.rgb(13, 16, 14)}));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(scroll, scrollParams);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(28), dp(22), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView eyebrow = text(getString(R.string.launcher_eyebrow), 11, 0xFFE7B64C, true);
        eyebrow.setLetterSpacing(0.12f);
        page.addView(eyebrow, matchWrap());

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(0, dp(14), 0, dp(14));
        page.addView(hero, matchWrap());

        ImageView cover = new ImageView(this);
        cover.setImageResource(R.drawable.madagascar_cover);
        cover.setContentDescription(getString(R.string.app_name));
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(roundRect(0xFF262A20, 18, 0xFF7C6B41, 1));
        cover.setClipToOutline(true);
        LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(dp(104), dp(104));
        coverParams.rightMargin = dp(18);
        hero.addView(cover, coverParams);

        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        hero.addView(titleStack, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView title = text("MADAGASCAR", 27, Color.WHITE, true);
        title.setLetterSpacing(0.025f);
        titleStack.addView(title, matchWrap());
        TextView subtitle = text(getString(R.string.launcher_subtitle), 14, 0xFFD5D2C5, false);
        subtitle.setPadding(0, dp(5), 0, 0);
        titleStack.addView(subtitle, matchWrap());
        TextView buildTag = text(getString(R.string.launcher_build_tag), 10, 0xFFE7B64C, true);
        buildTag.setLetterSpacing(0.08f);
        buildTag.setPadding(0, dp(12), 0, 0);
        titleStack.addView(buildTag, matchWrap());

        LinearLayout statusCard = card();
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(17), dp(16), dp(17), dp(16));
        statusTitle = text(getString(R.string.launcher_status_checking), 13, 0xFFE7B64C, true);
        statusTitle.setLetterSpacing(0.06f);
        statusCard.addView(statusTitle, matchWrap());
        statusDetail = text(getString(R.string.launcher_status_searching), 14, 0xFFE0E0D8, false);
        statusDetail.setPadding(0, dp(8), 0, 0);
        statusCard.addView(statusDetail, matchWrap());
        pathLabel = text("", 11, 0xFFAAA99D, false);
        pathLabel.setPadding(0, dp(9), 0, 0);
        statusCard.addView(pathLabel, matchWrap());

        page.addView(statusCard, cardParams());

        launchButton = button(getString(R.string.launcher_launch), 0xFF527B3C, 0xFFFFFFFF);
        launchButton.setOnClickListener(v -> launchGame());
        LinearLayout.LayoutParams launchParams = matchWrap();
        launchParams.topMargin = dp(18);
        page.addView(launchButton, launchParams);

        TextView addTitle = text(getString(R.string.launcher_add_copy), 12, 0xFFE7B64C, true);
        addTitle.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams addTitleParams = matchWrap();
        addTitleParams.topMargin = dp(25);
        addTitleParams.bottomMargin = dp(10);
        page.addView(addTitle, addTitleParams);

        isoButton = button(getString(R.string.launcher_import_iso), 0xFF272C23, 0xFFF4F0E7);
        isoButton.setOnClickListener(v -> discImporter.pick());
        page.addView(isoButton, matchWrap());

        folderButton = button(getString(R.string.launcher_import_folder), 0xFF272C23, 0xFFF4F0E7);
        folderButton.setOnClickListener(v -> pickImportFolder());
        LinearLayout.LayoutParams folderParams = matchWrap();
        folderParams.topMargin = dp(9);
        page.addView(folderButton, folderParams);

        TextView qualityTitle = text(getString(R.string.launcher_performance), 12, 0xFFE7B64C, true);
        qualityTitle.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams qualityTitleParams = matchWrap();
        qualityTitleParams.topMargin = dp(26);
        qualityTitleParams.bottomMargin = dp(10);
        page.addView(qualityTitle, qualityTitleParams);

        Button touchEditor = button(getString(R.string.launcher_touch_editor), 0xFF314B37, 0xFFFFFFFF);
        touchEditor.setOnClickListener(v -> startActivity(new Intent(this, TouchEditorActivity.class)));
        page.addView(touchEditor, matchWrap());

        TouchLayoutStore.Layout touchLayout = TouchLayoutStore.load(appDirectory);
        Switch touchEnabled = settingSwitch(touchLayout.enabled);
        touchEnabled.setOnCheckedChangeListener((button, enabled) -> {
            touchLayout.enabled = enabled;
            try {
                TouchLayoutStore.save(appDirectory, touchLayout);
                Toast.makeText(this, enabled ? R.string.launcher_touch_enabled_saved : R.string.launcher_touch_disabled_saved,
                        Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                Toast.makeText(this, getString(R.string.launcher_touch_save_failed, error.getMessage()), Toast.LENGTH_LONG).show();
            }
        });
        addSetting(page, R.string.launcher_touch_enabled, touchEnabled);

        Button graphics = button(getString(R.string.launcher_graphics), 0xFF272C23, 0xFFF4F0E7);
        graphics.setOnClickListener(v -> showGraphicsSettings());
        LinearLayout.LayoutParams graphicsParams = matchWrap();
        graphicsParams.topMargin = dp(9);
        page.addView(graphics, graphicsParams);

        LinearLayout tips = card();
        tips.setOrientation(LinearLayout.VERTICAL);
        tips.setPadding(dp(16), dp(14), dp(16), dp(14));
        addBullet(tips, getString(R.string.launcher_tip_settings));
        addBullet(tips, getString(R.string.launcher_tip_touch));
        addBullet(tips, getString(R.string.launcher_tip_mali));
        page.addView(tips, cardParams());

        Button credits = button(getString(R.string.launcher_credits), 0xFF272C23, 0xFFF4F0E7);
        credits.setOnClickListener(v -> showCreditsIfNeeded(true));
        LinearLayout.LayoutParams creditsParams = matchWrap();
        creditsParams.topMargin = dp(16);
        page.addView(credits, creditsParams);

        TextView legal = text(getString(R.string.launcher_legal), 12, 0xFFAAA99D, false);
        legal.setGravity(Gravity.CENTER);
        legal.setPadding(dp(8), dp(20), dp(8), dp(6));
        page.addView(legal, matchWrap());

        TextView version = text(getString(R.string.launcher_version), 10, 0xFF777A6E, true);
        version.setGravity(Gravity.CENTER);
        version.setLetterSpacing(0.08f);
        page.addView(version, matchWrap());
        return root;
    }

    /** Edits the TOML values read by the Android Vulkan host before launching the game. */
    private void showGraphicsSettings() {
        if (appDirectory == null) return;
        GraphicsSettings.Config config = GraphicsSettings.load(appDirectory);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(12), dp(18), dp(12));

        TextView intro = text(getString(R.string.graphics_description), 13, 0xFFD4D5CB, false);
        intro.setLineSpacing(dp(2), 1f);
        intro.setPadding(0, 0, 0, dp(8));
        form.addView(intro, matchWrap());

        Spinner aspect = choice(new String[] {getString(R.string.graphics_aspect_16_9),
                getString(R.string.graphics_aspect_21_9)}, config.aspectRatio);
        addSetting(form, R.string.graphics_aspect_ratio, aspect);
        Spinner renderer = choice(new String[] {getString(R.string.graphics_renderer_auto),
                getString(R.string.graphics_renderer_vulkan), getString(R.string.graphics_renderer_gles)}, config.renderer);
        addSetting(form, R.string.graphics_renderer, renderer);
        Spinner renderScale = choice(percentChoices(GraphicsSettings.RENDER_SCALES),
                selectedIndex(GraphicsSettings.RENDER_SCALES, config.renderPercent));
        addSetting(form, R.string.graphics_render_scale, renderScale);
        Spinner filter = choice(new String[] {getString(R.string.graphics_filter_off), getString(R.string.graphics_filter_crt_light),
                        getString(R.string.graphics_filter_crt_strong), getString(R.string.graphics_filter_sharp),
                        getString(R.string.graphics_filter_vibrant), getString(R.string.graphics_filter_noir),
                        getString(R.string.graphics_filter_sepia), getString(R.string.graphics_filter_night),
                        getString(R.string.graphics_filter_vhs)}, config.screenFilter);
        addSetting(form, R.string.graphics_screen_filter, filter);
        Spinner brightness = choice(numberChoices(GraphicsSettings.BRIGHTNESS),
                selectedIndex(GraphicsSettings.BRIGHTNESS, config.brightness));
        addSetting(form, R.string.graphics_brightness, brightness);
        Spinner oled = choice(new String[] {getString(R.string.graphics_oled_auto), getString(R.string.graphics_off),
                getString(R.string.graphics_on)}, config.oledScreen);
        addSetting(form, R.string.graphics_oled, oled);

        addSettingsHeading(form, R.string.graphics_performance_section);
        Spinner fps = choice(fpsChoices(GraphicsSettings.FPS_CAPS), selectedIndex(GraphicsSettings.FPS_CAPS, config.fpsCap));
        addSetting(form, R.string.graphics_fps_cap, fps);
        Switch vsync = settingSwitch(config.vsync);
        addSetting(form, R.string.graphics_vsync, vsync);
        Spinner aniso = choice(new String[] {getString(R.string.graphics_aniso_default), "2×", "4×", "8×", "16×"},
                selectedIndex(GraphicsSettings.ANISOTROPY, config.anisotropicFiltering));
        addSetting(form, R.string.graphics_anisotropic, aniso);
        Switch dynamic = settingSwitch(config.dynamicResolution);
        addSetting(form, R.string.graphics_dynamic, dynamic);
        Spinner target = choice(fpsChoices(GraphicsSettings.DYNAMIC_TARGETS),
                selectedIndex(GraphicsSettings.DYNAMIC_TARGETS, config.dynamicTargetFps));
        addSetting(form, R.string.graphics_dynamic_target, target);
        Spinner dynamicMin = choice(percentChoices(GraphicsSettings.DYNAMIC_MIN),
                selectedIndex(GraphicsSettings.DYNAMIC_MIN, config.dynamicMinPercent));
        addSetting(form, R.string.graphics_dynamic_min, dynamicMin);
        Spinner dynamicMax = choice(percentChoices(GraphicsSettings.DYNAMIC_MAX),
                selectedIndex(GraphicsSettings.DYNAMIC_MAX, config.dynamicMaxPercent));
        addSetting(form, R.string.graphics_dynamic_max, dynamicMax);
        Spinner recovery = choice(secondsChoices(GraphicsSettings.DYNAMIC_HOLD),
                selectedIndex(GraphicsSettings.DYNAMIC_HOLD, config.dynamicHold));
        addSetting(form, R.string.graphics_dynamic_hold, recovery);

        addSettingsHeading(form, R.string.graphics_quality_section);
        Spinner antiAliasing = choice(new String[] {getString(R.string.graphics_aa_off), getString(R.string.graphics_aa_fxaa),
                getString(R.string.graphics_aa_ssaa)}, config.antiAliasing);
        addSetting(form, R.string.graphics_anti_aliasing, antiAliasing);
        Switch bloom = settingSwitch(config.bloom);
        addSetting(form, R.string.graphics_bloom, bloom);
        Switch fog = settingSwitch(config.fog);
        addSetting(form, R.string.graphics_fog, fog);
        Switch hdr = settingSwitch(config.hdr);
        addSetting(form, R.string.graphics_hdr, hdr);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.graphics_title)
                .setView(scroll)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.graphics_save, (dialog, which) -> {
                    config.aspectRatio = aspect.getSelectedItemPosition();
                    config.renderer = renderer.getSelectedItemPosition();
                    config.renderPercent = GraphicsSettings.RENDER_SCALES[renderScale.getSelectedItemPosition()];
                    config.screenFilter = filter.getSelectedItemPosition();
                    config.brightness = GraphicsSettings.BRIGHTNESS[brightness.getSelectedItemPosition()];
                    config.oledScreen = oled.getSelectedItemPosition();
                    config.fpsCap = GraphicsSettings.FPS_CAPS[fps.getSelectedItemPosition()];
                    config.vsync = vsync.isChecked();
                    config.anisotropicFiltering = GraphicsSettings.ANISOTROPY[aniso.getSelectedItemPosition()];
                    config.dynamicResolution = dynamic.isChecked();
                    config.dynamicTargetFps = GraphicsSettings.DYNAMIC_TARGETS[target.getSelectedItemPosition()];
                    config.dynamicMinPercent = GraphicsSettings.DYNAMIC_MIN[dynamicMin.getSelectedItemPosition()];
                    config.dynamicMaxPercent = GraphicsSettings.DYNAMIC_MAX[dynamicMax.getSelectedItemPosition()];
                    config.dynamicHold = GraphicsSettings.DYNAMIC_HOLD[recovery.getSelectedItemPosition()];
                    config.antiAliasing = antiAliasing.getSelectedItemPosition();
                    config.bloom = bloom.isChecked();
                    config.fog = fog.isChecked();
                    config.hdr = hdr.isChecked();
                    try {
                        GraphicsSettings.save(appDirectory, config);
                        Toast.makeText(this, R.string.graphics_saved, Toast.LENGTH_SHORT).show();
                    } catch (Exception error) {
                        new AlertDialog.Builder(this).setTitle(R.string.graphics_save_failed_title)
                                .setMessage(getString(R.string.graphics_save_failed, error.getMessage()))
                                .setPositiveButton(R.string.dialog_ok, null).show();
                    }
                })
                .show();
    }

    private Spinner choice(String[] values, int selected) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(selected, values.length - 1)));
        return spinner;
    }

    private Switch settingSwitch(boolean checked) {
        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        toggle.setShowText(true);
        return toggle;
    }

    private void addSettingsHeading(LinearLayout form, int title) {
        TextView heading = text(getString(title), 12, 0xFFE7B64C, true);
        heading.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(17);
        params.bottomMargin = dp(4);
        form.addView(heading, params);
    }

    private void addSetting(LinearLayout form, int title, View control) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13), dp(7), dp(8), dp(7));
        row.setBackground(roundRect(0xFF242A21, 12, 0xFF404838, 1));
        TextView label = text(getString(title), 13, 0xFFF4F0E7, true);
        label.setPadding(0, 0, dp(8), 0);
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(control, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(6);
        form.addView(row, params);
    }

    private String[] percentChoices(int[] values) {
        String[] choices = new String[values.length];
        for (int i = 0; i < values.length; i++) choices[i] = getString(R.string.graphics_percent, values[i]);
        return choices;
    }

    private String[] fpsChoices(int[] values) {
        String[] choices = new String[values.length];
        for (int i = 0; i < values.length; i++) choices[i] = getString(R.string.graphics_fps, values[i]);
        return choices;
    }

    private String[] secondsChoices(int[] values) {
        String[] choices = new String[values.length];
        for (int i = 0; i < values.length; i++) choices[i] = getString(R.string.graphics_seconds, values[i]);
        return choices;
    }

    private String[] numberChoices(int[] values) {
        String[] choices = new String[values.length];
        for (int i = 0; i < values.length; i++) choices[i] = Integer.toString(values[i]);
        return choices;
    }

    private int selectedIndex(int[] values, int selected) {
        int nearest = 0;
        int distance = Integer.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == selected) return i;
            int next = Math.abs(values[i] - selected);
            if (next < distance) {
                nearest = i;
                distance = next;
            }
        }
        return nearest;
    }

    private void showCreditsIfNeeded(boolean force) {
        SharedPreferences preferences = getSharedPreferences("launcher", MODE_PRIVATE);
        if (!force && preferences.getBoolean("credits_shown", false)) return;
        TextView message = new TextView(this);
        int padding = dp(20);
        message.setPadding(padding, 0, padding, 0);
        message.setTextColor(0xFFF4F0E7);
        message.setTextSize(15);
        Spanned text = Html.fromHtml(getString(R.string.credits_message), Html.FROM_HTML_MODE_LEGACY);
        message.setText(text);
        message.setMovementMethod(LinkMovementMethod.getInstance());
        message.setLinksClickable(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.credits_title)
                .setView(message)
                .setPositiveButton(R.string.dialog_ok, null)
                .show();
        preferences.edit().putBoolean("credits_shown", true).apply();
    }

    private void addBullet(LinearLayout parent, String value) {
        TextView line = text("•  " + value, 13, 0xFFD4D5CB, false);
        line.setLineSpacing(dp(3), 1.0f);
        line.setPadding(0, dp(3), 0, dp(8));
        parent.addView(line, matchWrap());
    }

    private void pickImportFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQUEST_IMPORT_FOLDER);
        } catch (Exception e) {
            Toast.makeText(this, R.string.launcher_picker_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == GameImporter.REQUEST) {
            importing = true;
            setImportButtonsEnabled(false);
            discImporter.copy(uri, gameDirectory, ok -> {
                importing = false;
                setImportButtonsEnabled(true);
                refreshGameState();
            });
        } else if (requestCode == REQUEST_IMPORT_FOLDER) {
            try {
                // The tree is copied immediately into app-owned storage, so it stays usable even when the provider
                // cannot offer a persistable grant (common with cloud and USB document providers).
                importing = true;
                setImportButtonsEnabled(false);
                folderImporter.copy(uri, gameDirectory, ok -> {
                    importing = false;
                    setImportButtonsEnabled(true);
                    refreshGameState();
                });
            } catch (Exception e) {
                importing = false;
                setImportButtonsEnabled(true);
                showImportError(getString(R.string.launcher_folder_read_failed, e.getMessage()));
            }
        }
    }

    private void launchGame() {
        if (!hasGame(gameDirectory)) {
            refreshGameState();
            new AlertDialog.Builder(this).setTitle(R.string.launcher_missing_title)
                    .setMessage(R.string.launcher_missing_message)
                    .setPositiveButton(R.string.dialog_ok, null).show();
            return;
        }
        startActivity(new Intent(this, GameActivity.class));
    }

    private void refreshGameState() {
        if (!launcherReady || statusTitle == null || statusDetail == null || pathLabel == null
                || launchButton == null || gameDirectory == null) return;
        boolean found = hasGame(gameDirectory);
        statusTitle.setText(found ? R.string.launcher_status_ready : R.string.launcher_status_needed);
        statusTitle.setTextColor(found ? 0xFF8FC76A : 0xFFE7B64C);
        statusDetail.setText(found ? R.string.launcher_status_found
                : (importing ? R.string.launcher_status_importing : R.string.launcher_status_missing));
        pathLabel.setText(getString(R.string.launcher_path, gameDirectory.getAbsolutePath()));
        launchButton.setEnabled(found && !importing);
        launchButton.setAlpha(launchButton.isEnabled() ? 1.0f : 0.48f);
    }

    private void setImportButtonsEnabled(boolean enabled) {
        if (isoButton != null) isoButton.setEnabled(enabled);
        if (folderButton != null) folderButton.setEnabled(enabled);
        if (isoButton != null) isoButton.setAlpha(enabled ? 1.0f : 0.55f);
        if (folderButton != null) folderButton.setAlpha(enabled ? 1.0f : 0.55f);
        refreshGameState();
    }

    private void showImportError(String message) {
        new AlertDialog.Builder(this).setTitle(R.string.launcher_import_failed)
                .setMessage(message).setPositiveButton(R.string.dialog_ok, null).show();
    }

    private static boolean hasGame(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        if (new File(dir, "default.xbe").isFile()) return true;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            String name = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (f.isFile() && (name.endsWith(".iso") || name.endsWith(".xiso"))) return true;
        }
        return false;
    }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setBackground(roundRect(0xE51C211B, 18, 0xFF3A4032, 1));
        return view;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(6);
        return params;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(sp);
        v.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        v.setIncludeFontPadding(true);
        return v;
    }

    private Button button(String label, int background, int foreground) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(foreground);
        b.setTextSize(14);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setAllCaps(false);
        b.setMinHeight(dp(54));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setBackground(roundRect(background, 16, background == 0xFF527B3C ? 0xFF739C55 : 0xFF454A3D, 1));
        b.setStateListAnimator(null);
        return b;
    }

    private GradientDrawable roundRect(int fill, int radius, int stroke, int width) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        if (width > 0) d.setStroke(dp(width), stroke);
        return d;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
