package com.fuzz.volumehu;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * File:        TraceLog.java
 * Description: A plain step-by-step trace file, appended to on disk at
 *              every meaningful checkpoint through startup (not just on an
 *              uncaught exception - see FuzzVolumeApp for that). The point
 *              is to see exactly which line ran last before something died,
 *              which a single final stack trace can't show if the failure
 *              is a native crash, an ANR, or anything else that never
 *              reaches Java's uncaught-exception handler.
 *
 *              Written to getExternalFilesDir() (falling back to the
 *              app-private dir if that's unavailable) specifically so it
 *              can be read with an ordinary file manager - no adb, and not
 *              even a working copy of this app - at
 *              Android/data/com.fuzz.volumehu/files/fuzz_volume_trace.log
 *              on the device's own storage.
 * Author:      FuzzBC
 * Date:        2026-09-01
 */
public final class TraceLog {

    private static final String FILE_NAME = "fuzz_volume_trace.log";

    private TraceLog() {}

    public static void step(Context ctx, String where) {
        write(ctx, "STEP", where, null);
    }

    public static void error(Context ctx, String where, Throwable t) {
        write(ctx, "ERROR", where, t);
    }

    private static synchronized void write(Context ctx, String level, String msg, Throwable t) {
        try {
            File f = logFile(ctx);
            try (FileWriter fw = new FileWriter(f, true)) {
                String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
                fw.write(ts + " [" + level + "] " + msg + "\n");
                if (t != null) {
                    StringWriter sw = new StringWriter();
                    t.printStackTrace(new PrintWriter(sw));
                    fw.write(sw.toString());
                    fw.write("\n");
                }
            }
        } catch (Throwable ignored) {
            // Logging itself must never throw and mask the real problem.
        }
    }

    /**
     * Where the log actually lives - shown in the UI so it can be found with
     * a file manager. Prefers the root of shared storage (a plain
     * /storage/emulated/0/fuzz_volume_trace.log any file manager can see
     * without digging into a per-app folder) when "all files access" has
     * been granted; otherwise the app's own external-files folder; and
     * app-private storage as the last resort if neither is available.
     */
    public static File logFile(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (Environment.isExternalStorageManager()) {
                    return new File(Environment.getExternalStorageDirectory(), FILE_NAME);
                }
            } catch (Throwable ignored) {}
        }
        File dir;
        try {
            dir = ctx.getExternalFilesDir(null);
        } catch (Throwable t) {
            dir = null;
        }
        if (dir == null || (!dir.exists() && !dir.mkdirs())) {
            dir = ctx.getFilesDir();
        }
        return new File(dir, FILE_NAME);
    }

    /** True once "all files access" is granted and the log is writing to shared storage's root. */
    public static boolean isOnMainStorage(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        try {
            return Environment.isExternalStorageManager();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Reads the whole trace log as text, or null if there isn't one yet.
     * Checks the current preferred location first, then falls back to the
     * app's external-files folder - relevant right after "all files access"
     * is freshly granted, when older entries are still sitting in the old
     * location and nothing's been logged to the new one yet.
     */
    public static String readAll(Context ctx) {
        String primary = readFile(logFile(ctx));
        if (primary != null) return primary;
        File fallbackDir = ctx.getExternalFilesDir(null);
        if (fallbackDir != null) return readFile(new File(fallbackDir, FILE_NAME));
        return null;
    }

    /**
     * The most recent maxChars of the log (the file only ever grows -
     * appended to across every launch since v1.009 - so after many test
     * cycles it can get long; the most recent entries are what matter for
     * diagnosing the latest crash, and a huge TextView is worth avoiding).
     */
    public static String readTail(Context ctx, int maxChars) {
        String full = readAll(ctx);
        if (full == null) return null;
        if (full.length() <= maxChars) return full;
        int cut = full.length() - maxChars;
        int nl = full.indexOf('\n', cut);
        return "...(earlier entries trimmed)...\n" + full.substring(nl >= 0 ? nl + 1 : cut);
    }

    private static String readFile(File f) {
        if (f == null || !f.exists()) return null;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } catch (Exception e) {
            return null;
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
