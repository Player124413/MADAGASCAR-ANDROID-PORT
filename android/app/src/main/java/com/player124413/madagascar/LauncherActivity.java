package com.player124413.madagascar;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/** The standalone launcher and legal game-file setup screen. */
public final class LauncherActivity extends Activity {
    private static final int REQUEST_IMPORT_FOLDER = 0x4D42;

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
            gameDirectory = new File(files, "game");
            gameDirectory.mkdirs();
            discImporter = new GameImporter(this);
            folderImporter = new FolderImporter(this);

            setContentView(buildLauncher());
            refreshGameState();
        } catch (Throwable error) {
            // Keep the launch activity alive long enough to show a report location instead of immediately returning
            // to the home screen on an OEM-specific framework/resource failure.
            MadagascarApplication.writeFailure(this, "Launcher initialization failed", error);
            showStartupFailure(error);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshGameState();
    }

    private void showStartupFailure(Throwable error) {
        TextView view = new TextView(this);
        int padding = dp(24);
        view.setPadding(padding, padding, padding, padding);
        view.setTextColor(Color.WHITE);
        view.setTextSize(16);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setText("Не удалось открыть лаунчер Madagascar.\n\n"
                + error.getClass().getSimpleName() + ": "
                + (error.getMessage() == null ? "без сообщения" : error.getMessage())
                + "\n\nОтчёт сохранён в Android/data/com.player124413.madagascar/files/logs/java-startup-crash.txt."
                + "\n\nПришлите его содержимое вместе с моделью телефона и версией Android.");
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

        TextView eyebrow = text("STATIC RECOMPILATION  •  ANDROID", 11, 0xFFE7B64C, true);
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
        TextView subtitle = text("Приключение начинается здесь", 14, 0xFFD5D2C5, false);
        subtitle.setPadding(0, dp(5), 0, 0);
        titleStack.addView(subtitle, matchWrap());
        TextView buildTag = text("VULKAN 1.1  •  ARM64  •  TOUCH", 10, 0xFFE7B64C, true);
        buildTag.setLetterSpacing(0.08f);
        buildTag.setPadding(0, dp(12), 0, 0);
        titleStack.addView(buildTag, matchWrap());

        LinearLayout statusCard = card(page);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(17), dp(16), dp(17), dp(16));
        statusTitle = text("ПРОВЕРКА ФАЙЛОВ ИГРЫ", 13, 0xFFE7B64C, true);
        statusTitle.setLetterSpacing(0.06f);
        statusCard.addView(statusTitle, matchWrap());
        statusDetail = text("Ищем default.xbe или образ диска…", 14, 0xFFE0E0D8, false);
        statusDetail.setPadding(0, dp(8), 0, 0);
        statusCard.addView(statusDetail, matchWrap());
        pathLabel = text("", 11, 0xFFAAA99D, false);
        pathLabel.setPadding(0, dp(9), 0, 0);
        statusCard.addView(pathLabel, matchWrap());

        page.addView(statusCard, matchWrap());

        launchButton = button("ЗАПУСТИТЬ ИГРУ", 0xFF527B3C, 0xFFFFFFFF);
        launchButton.setOnClickListener(v -> launchGame());
        LinearLayout.LayoutParams launchParams = matchWrap();
        launchParams.topMargin = dp(18);
        page.addView(launchButton, launchParams);

        TextView addTitle = text("ДОБАВИТЬ СВОЮ КОПИЮ", 12, 0xFFE7B64C, true);
        addTitle.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams addTitleParams = matchWrap();
        addTitleParams.topMargin = dp(25);
        addTitleParams.bottomMargin = dp(10);
        page.addView(addTitle, addTitleParams);

        isoButton = button("Импортировать Xbox ISO", 0xFF272C23, 0xFFF4F0E7);
        isoButton.setOnClickListener(v -> discImporter.pick());
        page.addView(isoButton, matchWrap());

        folderButton = button("Импортировать папку с default.xbe", 0xFF272C23, 0xFFF4F0E7);
        folderButton.setOnClickListener(v -> pickImportFolder());
        LinearLayout.LayoutParams folderParams = matchWrap();
        folderParams.topMargin = dp(9);
        page.addView(folderButton, folderParams);

        TextView qualityTitle = text("УПРАВЛЕНИЕ И ПРОИЗВОДИТЕЛЬНОСТЬ", 12, 0xFFE7B64C, true);
        qualityTitle.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams qualityTitleParams = matchWrap();
        qualityTitleParams.topMargin = dp(26);
        qualityTitleParams.bottomMargin = dp(10);
        page.addView(qualityTitle, qualityTitleParams);

        LinearLayout tips = card(page);
        tips.setOrientation(LinearLayout.VERTICAL);
        tips.setPadding(dp(16), dp(14), dp(16), dp(14));
        addBullet(tips, "Плавающая кнопка-шестерёнка открывает меню настроек во время игры.");
        addBullet(tips, "В «Сенсорном управлении» доступны пресеты, масштаб, прозрачность и редактор расположения. Кнопки можно перетаскивать, скрывать и менять их размер.");
        addBullet(tips, "Для Mali предусмотрены запасные Vulkan 1.1 пути, CPU-декодирование DXT и динамическое разрешение.");
        page.addView(tips, matchWrap());

        TextView legal = text("Игра не входит в приложение. Используйте файлы со своей законно полученной копии Madagascar для Xbox.",
                12, 0xFFAAA99D, false);
        legal.setGravity(Gravity.CENTER);
        legal.setPadding(dp(8), dp(20), dp(8), dp(6));
        page.addView(legal, matchWrap());

        TextView version = text("MADAGASCAR ANDROID  •  EXPERIMENTAL BUILD", 10, 0xFF777A6E, true);
        version.setGravity(Gravity.CENTER);
        version.setLetterSpacing(0.08f);
        page.addView(version, matchWrap());
        return root;
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
            Toast.makeText(this, "Не удалось открыть выбор папки", Toast.LENGTH_LONG).show();
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
                showImportError("Не удалось прочитать выбранную папку: " + e.getMessage());
            }
        }
    }

    private void launchGame() {
        if (!hasGame(gameDirectory)) {
            refreshGameState();
            new AlertDialog.Builder(this).setTitle("Файлы игры не найдены")
                    .setMessage("Добавьте ISO либо выберите/импортируйте папку с default.xbe.")
                    .setPositiveButton("ОК", null).show();
            return;
        }
        startActivity(new Intent(this, GameActivity.class));
    }

    private void refreshGameState() {
        if (statusTitle == null || gameDirectory == null) return;
        boolean found = hasGame(gameDirectory);
        statusTitle.setText(found ? "ФАЙЛЫ ГОТОВЫ" : "НУЖНЫ ФАЙЛЫ ИГРЫ");
        statusTitle.setTextColor(found ? 0xFF8FC76A : 0xFFE7B64C);
        statusDetail.setText(found
                ? "Игра найдена. Порт запускается нативно на ARM64 через Vulkan."
                : (importing ? "Идёт импорт. Не закрывайте приложение." : "Добавьте ISO или папку с default.xbe."));
        pathLabel.setText("Папка: " + gameDirectory.getAbsolutePath());
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
        new AlertDialog.Builder(this).setTitle("Импорт не выполнен")
                .setMessage(message).setPositiveButton("ОК", null).show();
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

    private LinearLayout card(LinearLayout parent) {
        LinearLayout view = new LinearLayout(this);
        view.setBackground(roundRect(0xE51C211B, 18, 0xFF3A4032, 1));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(6);
        parent.addView(view, params);
        return view;
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
