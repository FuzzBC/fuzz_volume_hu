package com.fuzz.volumehu;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * File:        VolumeKeyAccessibilityService.java
 * Description: The actual mechanism behind "Block system volume popup"
 *              (MainActivity toggle) - an AccessibilityService with
 *              canRequestFilterKeyEvents, which gets first look at every
 *              hardware key press system-wide, before Android's own
 *              special-cased volume handling ever sees it. Consuming
 *              (returning true from) a volume key here stops the system
 *              from processing it at all - no volume change, no native
 *              popup - and this applies the change itself instead, via
 *              AudioManager.adjustStreamVolume() with flags=0 (no
 *              FLAG_SHOW_UI), so it happens silently. VolumeOverlayService's
 *              own volumeReceiver (VOLUME_CHANGED_ACTION) still fires
 *              exactly as it always did, since that adjustStreamVolume()
 *              call is a real volume change like any other - see its
 *              maybePeekPanel() for the "open for 2s, then close" half of
 *              this feature.
 *
 *              Holding the button down: an accessibility key filter sits
 *              *before* the normal focused-window input queue, and the
 *              OS's own key-repeat generation is tied to that queue, not
 *              guaranteed to reach a filter intercepting ahead of it - in
 *              testing, a held button only ever delivered the single
 *              initial ACTION_DOWN with no further repeats, which felt like
 *              volume "crawling" no matter how long the button stayed
 *              down. So this owns its own repeat timer instead of
 *              depending on OS-generated repeat events at all: one
 *              immediate step on the initial press (so a quick tap is
 *              exactly one step), then - if still held past
 *              INITIAL_REPEAT_DELAY_MS - repeated steps every
 *              REPEAT_INTERVAL_MS until ACTION_UP. If the OS *does* also
 *              deliver its own repeat DOWN events for the same direction
 *              while already held, those are ignored (this loop already
 *              covers it) rather than risking a double-step.
 *
 *              Enabling this service (Android Settings > Accessibility)
 *              only makes it *eligible* to run - it checks
 *              Prefs.isBlockNativeVolumeUi() fresh on every key press and
 *              is a complete no-op (event passed straight through
 *              untouched) whenever that's off, so granting Accessibility
 *              access alone doesn't change anything until the main
 *              screen's toggle is also turned on.
 *
 *              Tried and reverted: a window-watch backstop for hardware
 *              where the native popup shows up anyway, using
 *              TYPE_WINDOW_STATE_CHANGED + performGlobalAction(BACK) to
 *              back out of it the instant it appeared. Verified live (adb
 *              dumpsys window) that the popup is its own protected system
 *              window (com.android.systemui, class
 *              VolumeDialogImpl$CustomDialog on the Samsung S25 Ultra this
 *              was tested on) and that GLOBAL_ACTION_BACK does nothing to
 *              it - Android doesn't let an accessibility service dismiss
 *              system UI that way, by design. Worse, BACK doesn't just
 *              fail silently: it still gets delivered to whatever's
 *              actually focused, so every volume press was sending a
 *              spurious back-press into the foreground app (confirmed live
 *              - it backed the test device out to its launcher). Pulled
 *              entirely rather than ship a feature that does nothing to
 *              the popup while quietly navigating the driver's foreground
 *              app backward on every volume tap.
 * Author:      FuzzBC
 * Date:        2026-09-03
 */
public class VolumeKeyAccessibilityService extends AccessibilityService {

    private static final long INITIAL_REPEAT_DELAY_MS = 400;
    private static final long REPEAT_INTERVAL_MS = 40;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 0 = no key currently held; otherwise AudioManager.ADJUST_RAISE/LOWER for whichever is. */
    private int heldDirection = 0;
    private final Runnable repeatRunnable = new Runnable() {
        @Override public void run() {
            if (heldDirection == 0) return;
            applyStep(heldDirection, false);
            handler.postDelayed(this, REPEAT_INTERVAL_MS);
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Unused - see class doc for the window-watch approach that was
        // tried here and reverted (ineffective against protected system UI,
        // and had a real side effect of its own). This service only cares
        // about onKeyEvent(); the config XML's accessibilityEventTypes is
        // back to the narrowest value the schema allows.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        try {
            int code = event.getKeyCode();
            if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
                return super.onKeyEvent(event);
            }
            String keyName = code == KeyEvent.KEYCODE_VOLUME_UP ? "VOLUME_UP" : "VOLUME_DOWN";
            boolean blockOn = new Prefs(this).isBlockNativeVolumeUi();

            // repeatCount==0 is the actual physical press-down, whether or
            // not the OS also sends its own repeat DOWNs afterward (see
            // class doc) - logging only this line, once per real press,
            // is what tells us on a head unit with no adb whether this
            // service is even receiving the key at all, and whether the
            // block toggle read as on at that moment. If the head unit's
            // own volume popup still shows up despite this line reading
            // "blockEnabled=true" and the value below actually changing,
            // that popup isn't tied to the standard key-event pipeline at
            // all (some OEM launchers show their own volume HUD off the
            // raw stream value changing, independent of FLAG_SHOW_UI or
            // who changed it) - nothing at this level can suppress that.
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                TraceLog.step(this, "VolumeKey " + keyName + " pressed, blockEnabled=" + blockOn);
            }

            if (!blockOn) {
                stopRepeating(); // turned off mid-press - don't leave a stray loop running
                return super.onKeyEvent(event);
            }

            int direction = code == KeyEvent.KEYCODE_VOLUME_UP
                    ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;

            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (heldDirection != direction) {
                    heldDirection = direction;
                    applyStep(direction, true); // instant feedback for a plain tap
                    handler.removeCallbacks(repeatRunnable);
                    handler.postDelayed(repeatRunnable, INITIAL_REPEAT_DELAY_MS);
                }
                // else: this is either our own already-running loop's
                // effect or an OS repeat for the same direction we're
                // already covering - either way, nothing new to do.
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (heldDirection == direction) stopRepeating();
            }
            return true; // consume - the system never processes this key at all
        } catch (Exception e) {
            android.util.Log.e("VolumeKeyAccessibilityService", "onKeyEvent failed", e);
            TraceLog.error(this, "VolumeKeyAccessibilityService.onKeyEvent failed", e);
            return super.onKeyEvent(event);
        }
    }

    /** @param logResult True only for the first step of a press - every
     *  40ms repeat tick calling this while held would flood the trace log
     *  for no diagnostic benefit; the first step per press is what actually
     *  proves adjustStreamVolume() is taking effect on this device. */
    private void applyStep(int direction, boolean logResult) {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                if (logResult) TraceLog.error(this, "applyStep: AudioManager unavailable", new IllegalStateException("no AudioManager"));
                return;
            }
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
            if (logResult) {
                int now = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                TraceLog.step(this, "applyStep: adjustStreamVolume applied, STREAM_MUSIC now=" + now);
            }
        } catch (Exception e) {
            android.util.Log.e("VolumeKeyAccessibilityService", "applyStep failed", e);
            TraceLog.error(this, "applyStep failed", e);
        }
    }

    private void stopRepeating() {
        heldDirection = 0;
        handler.removeCallbacks(repeatRunnable);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            TraceLog.step(this, "VolumeKeyAccessibilityService connected");
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // Accessibility got disabled (or the app is being torn down) mid-hold - don't leave the timer running.
        stopRepeating();
        return super.onUnbind(intent);
    }
}
