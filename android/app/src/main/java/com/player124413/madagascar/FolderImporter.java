package com.player124413.madagascar;

import android.app.Activity;
import android.app.AlertDialog;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.function.Consumer;

/** Copies an extracted game folder selected with Android's Storage Access Framework into app-owned storage. */
final class FolderImporter {
    private static final String TAG = "MadagascarImport";
    private static final int BUFFER_SIZE = 1 << 20;
    private static final String[] COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
    };

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;

    FolderImporter(Activity activity) {
        this.activity = activity;
    }

    void copy(Uri tree, File destination, Consumer<Boolean> done) {
        File parent = destination.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.isDirectory() && !parent.mkdirs()) {
            message("Импорт папки", "Не удалось подготовить папку приложения для файлов игры.", () -> done.accept(false));
            return;
        }

        File staging = new File(parent, "game.importing");
        File backup = new File(parent, "game.previous");
        deleteTree(staging);
        if (!staging.mkdirs()) {
            message("Импорт папки", "Не удалось создать временную папку для импорта.", () -> done.accept(false));
            return;
        }

        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
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
                .setTitle("Импорт папки игры")
                .setMessage("Большая папка может копироваться несколько минут.")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Отмена", (d, which) -> cancelled = true)
                .show();
        if (progress.getWindow() != null) progress.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Thread worker = new Thread(() -> {
            String error = null;
            try {
                String rootId = DocumentsContract.getTreeDocumentId(tree);
                Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
                copyDirectory(tree, root, staging, text);
                if (cancelled) throw new ImportCancelled();
                if (!new File(staging, "default.xbe").isFile()) {
                    throw new IllegalArgumentException("В выбранной папке не найден default.xbe. Выберите папку, где он лежит непосредственно внутри.");
                }
            } catch (ImportCancelled e) {
                // Cancellation removes the incomplete staging directory and leaves the current game intact.
            } catch (Exception e) {
                Log.w(TAG, "folder import failed", e);
                error = e.getMessage() == null ? "Не удалось прочитать выбранную папку." : e.getMessage();
            }

            boolean ok = error == null && !cancelled;
            if (ok) {
                deleteTree(backup);
                boolean oldMoved = !destination.exists() || destination.renameTo(backup);
                boolean newMoved = oldMoved && staging.renameTo(destination);
                if (!newMoved) {
                    if (oldMoved && backup.exists()) backup.renameTo(destination);
                    ok = false;
                    error = "Не удалось заменить текущую папку игры. Освободите место и попробуйте ещё раз.";
                } else {
                    deleteTree(backup);
                }
            }
            if (!ok) deleteTree(staging);

            final boolean result = ok;
            final String reason = error;
            main.post(() -> {
                progress.dismiss();
                if (result) {
                    message("Файлы игры готовы", "Папка Madagascar импортирована. Можно запускать игру.", () -> done.accept(true));
                } else if (reason != null) {
                    message("Импорт не выполнен", reason, () -> done.accept(false));
                } else {
                    done.accept(false);
                }
            });
        }, "MadagascarFolderImport");
        worker.start();
    }

    private void copyDirectory(Uri tree, Uri directory, File output, TextView progress) throws Exception {
        if (cancelled) throw new ImportCancelled();
        if (!output.isDirectory() && !output.mkdirs()) throw new IllegalArgumentException("Не удалось создать " + output.getName());

        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getDocumentId(directory));
        try (Cursor cursor = activity.getContentResolver().query(children, COLUMNS, null, null, null)) {
            if (cursor == null) throw new IllegalArgumentException("Системный файловый провайдер не открыл папку.");
            int idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int typeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                if (cancelled) throw new ImportCancelled();
                String id = cursor.getString(idColumn);
                String name = safeName(cursor.getString(nameColumn));
                if (name == null) continue;
                String mime = cursor.getString(typeColumn);
                Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                File target = new File(output, name);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    copyDirectory(tree, child, target, progress);
                } else {
                    copyFile(child, target, progress);
                }
            }
        }
    }

    private void copyFile(Uri source, File target, TextView progress) throws Exception {
        long copied = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        InputStream opened = activity.getContentResolver().openInputStream(source);
        if (opened == null) throw new IllegalArgumentException("Системный файловый провайдер не открыл файл.");
        try (InputStream in = new BufferedInputStream(opened, BUFFER_SIZE);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(target), BUFFER_SIZE)) {
            for (int count; !cancelled && (count = in.read(buffer)) != -1;) {
                out.write(buffer, 0, count);
                copied += count;
                if ((copied & ((16L << 20) - 1)) < BUFFER_SIZE) {
                    long bytes = copied;
                    main.post(() -> progress.setText(String.format(Locale.ROOT, "Скопировано %.1f МБ", bytes / 1048576.0)));
                }
            }
            out.flush();
        }
        if (cancelled) throw new ImportCancelled();
    }

    private static String safeName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0) return null;
        return name;
    }

    private static boolean deleteTree(File file) {
        if (file == null || !file.exists()) return true;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        return file.delete();
    }

    private void message(String title, String text, Runnable after) {
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton("ОК", null)
                .setOnDismissListener(d -> { if (after != null) after.run(); })
                .show();
    }

    private static final class ImportCancelled extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
