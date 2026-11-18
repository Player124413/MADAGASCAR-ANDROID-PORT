package com.player124413.madagascar;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

// Copies a disc image (.iso) the player picks with Android's file picker (Downloads, a cloud drive, a USB stick)
// into the folder the game is read from. The copy is written as .part and renamed at the end: the game looks for
// any .iso there every second and would open a half written one.
final class GameImporter {
    static final int REQUEST = 0x8A11;
    private static final String TAG = "MadagascarImport";
    private static final byte[] MAGIC = "MICROSOFT*XBOX*MEDIA".getBytes();
    // Match runtime/kernel/disc.cpp: a game-partition image or an original Xbox disc dump after its video partition.
    private static final long[] PARTITIONS = {0, 0x18300000L};
    private static final int BUFFER = 4 << 20;

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;

    GameImporter(Activity activity) {
        this.activity = activity;
    }

    // the picker; any file type, since phones rarely know a type for .iso
    boolean pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            activity.startActivityForResult(i, REQUEST);
            return true;
        } catch (ActivityNotFoundException e) {
            message("Импорт игры", "На устройстве нет файлового менеджера для выбора образа диска.", null);
            return false;
        }
    }

    // the picked file into dir; done runs on the main thread with true once the image is in place
    void copy(Uri uri, File dir, Consumer<Boolean> done) {
        String name = displayName(uri);
        long size = size(uri);
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".iso")) {
            message("Импорт игры", "Выберите образ диска игры — файл с расширением .iso.", () -> done.accept(false));
            return;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            message("Импорт игры", "Не удалось создать папку для файлов игры.", () -> done.accept(false));
            return;
        }
        if (size > 0 && dir.getUsableSpace() < size + (64L << 20)) {
            message("Недостаточно места", String.format(Locale.ROOT,
                    "Для образа нужно %.1f ГБ свободного места, доступно %.1f ГБ.", size / 1e9,
                    dir.getUsableSpace() / 1e9), () -> done.accept(false));
            return;
        }

        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(size <= 0);
        TextView text = new TextView(activity);
        text.setText("Подготовка…");
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(bar);
        box.addView(text);
        cancelled = false;
        AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle("Импорт: " + name)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Отмена", (d, w) -> cancelled = true)
                .show();
        progress.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        File part = new File(dir, name + ".part");
        File iso = new File(dir, name);
        File backup = new File(dir, name + ".previous");
        part.delete();
        backup.delete();
        new Thread(() -> {
            String error = null;
            try (ParcelFileDescriptor pfd = activity.getContentResolver().openFileDescriptor(uri, "r");
                 FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
                 FileChannel src = in.getChannel()) {
                if (isXboxImage(src) == Boolean.FALSE) {
                    error = name + " не является образом диска Xbox.";
                } else {
                    try (FileOutputStream out = new FileOutputStream(part); FileChannel dst = out.getChannel()) {
                        ByteBuffer buf = ByteBuffer.allocateDirect(BUFFER);
                        long copied = 0, shown = 0;
                        while (!cancelled && src.read(buf) > 0) {
                            buf.flip();
                            while (buf.hasRemaining()) copied += dst.write(buf);
                            buf.clear();
                            if (copied - shown >= (16L << 20)) {
                                shown = copied;
                                long c = copied;
                                main.post(() -> show(bar, text, c, size));
                            }
                        }
                        out.getFD().sync();
                        if (!cancelled && size > 0 && copied != size) error = "Копирование завершилось раньше времени.";
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "import failed", e);
                error = "Ошибка копирования: " + e.getMessage();
            }
            boolean ok = false;
            if (error == null && !cancelled) {
                boolean hadPrevious = !iso.exists() || iso.renameTo(backup);
                ok = hadPrevious && part.renameTo(iso);
                if (ok) backup.delete();
                else if (hadPrevious && backup.exists()) backup.renameTo(iso);
            }
            if (!ok) {
                part.delete();
                if (error == null && !cancelled) error = "Не удалось сохранить образ в папке игры.";
            }
            String why = error;
            main.post(() -> {
                progress.dismiss();
                if (ok) {
                    Log.i(TAG, "imported " + iso);
                    message("Файлы игры готовы", name + " сохранён. Теперь можно запускать игру.", () -> done.accept(true));
                } else if (why != null) {
                    message("Импорт не выполнен", why, () -> done.accept(false));
                } else {
                    done.accept(false);  // cancelled
                }
            });
        }, "GameImport").start();
    }

    private static void show(ProgressBar bar, TextView text, long copied, long size) {
        if (size > 0) {
            bar.setProgress((int) (copied * 1000 / size));
            text.setText(String.format(Locale.ROOT, "%.2f из %.2f ГБ", copied / 1e9, size / 1e9));
        } else {
            text.setText(String.format(Locale.ROOT, "%.2f ГБ", copied / 1e9));
        }
    }

    // null when the file cannot be read out of order (some cloud drives stream it): copied unchecked then, the game
    // tells of a wrong image itself. Positional reads leave the channel at the start for the copy.
    private static Boolean isXboxImage(FileChannel ch) {
        ByteBuffer b = ByteBuffer.allocate(MAGIC.length);
        try {
            for (long part : PARTITIONS) {
                b.clear();
                if (ch.read(b, part + 32 * 2048) == MAGIC.length && Arrays.equals(b.array(), MAGIC)) return true;
            }
            return false;
        } catch (Exception e) {
            Log.i(TAG, "image not checked, no seeking: " + e);
            return null;
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = activity.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return new File(c.getString(0)).getName();
        } catch (Exception e) {
            // fall back on the path
        }
        String last = uri.getLastPathSegment();
        return last == null ? null : new File(last).getName();
    }

    private long size(Uri uri) {
        try (Cursor c = activity.getContentResolver().query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception e) {
            // unknown: the progress has no end then
        }
        return -1;
    }

    private void message(String title, String text, Runnable after) {
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton("ОК", null)
                .setOnDismissListener(d -> {
                    if (after != null) after.run();
                })
                .show();
    }
}
