package com.player124413.madagascar;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/** Saves uncaught Java failures in app-owned storage so a device-only startup issue is diagnosable. */
public final class MadagascarApplication extends Application {
    private static final String TAG = "MadagascarCrash";
    private Thread.UncaughtExceptionHandler previousHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            writeFailure(this, "Uncaught Java exception on " + thread.getName(), error);
            if (previousHandler != null) previousHandler.uncaughtException(thread, error);
        });
    }

    static void writeFailure(Context context, String heading, Throwable error) {
        try {
            File root = context.getExternalFilesDir(null);
            if (root == null) root = context.getFilesDir();
            File logs = new File(root, "logs");
            if (!logs.isDirectory() && !logs.mkdirs()) return;
            File report = new File(logs, "java-startup-crash.txt");
            StringWriter trace = new StringWriter();
            PrintWriter writer = new PrintWriter(trace);
            writer.println(heading);
            writer.println("device=" + Build.MANUFACTURER + " " + Build.MODEL);
            writer.println("android=" + Build.VERSION.RELEASE + " api=" + Build.VERSION.SDK_INT);
            error.printStackTrace(writer);
            writer.flush();
            try (FileOutputStream output = new FileOutputStream(report, false)) {
                output.write(trace.toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            Log.e(TAG, "Saved Java crash report to " + report.getAbsolutePath(), error);
        } catch (Exception ignored) {
            // Never replace the original process failure with a diagnostics failure.
        }
    }
}
