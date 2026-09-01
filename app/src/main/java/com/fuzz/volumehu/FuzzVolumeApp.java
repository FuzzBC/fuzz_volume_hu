package com.fuzz.volumehu;

import android.app.Application;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * File:        FuzzVolumeApp.java
 * Description: There's no adb on the target head units, so this is the
 *              whole crash-reporting pipeline: catch anything uncaught,
 *              anywhere in the app, write its stack trace to a plain file
 *              in app-private storage, then let the platform's own handler
 *              still run (so behavior otherwise stays normal - the process
 *              still dies the way it always would). MainActivity reads that
 *              file back on its next launch and shows it in a dialog, so a
 *              crash becomes something that can be read and reported
 *              instead of an invisible "it force closed" with no detail.
 * Author:      FuzzBC
 * Date:        2026-09-01
 */
public class FuzzVolumeApp extends Application {

    private static final String CRASH_FILE = "last_crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler platformHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                writeCrash(ex);
            } catch (Exception ignored) {
                // Writing the crash log must never itself throw and mask the real crash.
            }
            try {
                // A crash doesn't reliably run VolumeOverlayService.onDestroy(), so this
                // flag can't be trusted to have been cleared there - clear it here too,
                // otherwise MainActivity thinks the (now-dead) overlay is still running.
                new Prefs(this).setOverlayStarted(false);
            } catch (Exception ignored) {}
            if (platformHandler != null) {
                platformHandler.uncaughtException(thread, ex);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(1);
            }
        });
    }

    private void writeCrash(Throwable ex) throws Exception {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        File f = new File(getFilesDir(), CRASH_FILE);
        try (FileWriter fw = new FileWriter(f, false)) {
            fw.write("FuZz Volume HU crashed at " + new java.util.Date() + "\n\n");
            fw.write(sw.toString());
        }
    }

    /** Returns the last crash's text and deletes the file, or null if there wasn't one. */
    public static String takeLastCrash(android.content.Context ctx) {
        File f = new File(ctx.getFilesDir(), CRASH_FILE);
        if (!f.exists()) return null;
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } catch (Exception e) {
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        return sb.toString();
    }
}
