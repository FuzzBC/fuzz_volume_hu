package com.fuzz.volumehu;

import android.content.Context;

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

    /** Where the log actually lives - shown in the UI so it can be found with a file manager. */
    public static File logFile(Context ctx) {
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

    /** Reads the whole trace log as text, or null if there isn't one yet. */
    public static String readAll(Context ctx) {
        File f = logFile(ctx);
        if (!f.exists()) return null;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } catch (Exception e) {
            return null;
        }
        return sb.toString();
    }
}
