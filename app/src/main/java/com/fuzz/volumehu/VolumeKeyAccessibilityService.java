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
 * Author:      FuzzBC
 * Date:        2026-09-03
 */
public class VolumeKeyAccessibilityService extends AccessibilityService {

    private static final long INITIAL_REPEAT_DELAY_MS = 400;
    private static final long REPEAT_INTERVAL_MS = 130;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 0 = no key currently held; otherwise AudioManager.ADJUST_RAISE/LOWER for whichever is. */
    private int heldDirection = 0;
    private final Runnable repeatRunnable = new Runnable() {
        @Override public void run() {
            if (heldDirection == 0) return;
            applyStep(heldDirection);
            handler.postDelayed(this, REPEAT_INTERVAL_MS);
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Unused - this service only cares about onKeyEvent(); the config
        // XML's accessibilityEventTypes is set to the narrowest value the
        // schema allows since no actual event stream is consumed here.
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
            if (!new Prefs(this).isBlockNativeVolumeUi()) {
                stopRepeating(); // turned off mid-press - don't leave a stray loop running
                return super.onKeyEvent(event);
            }

            int direction = code == KeyEvent.KEYCODE_VOLUME_UP
                    ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;

            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (heldDirection != direction) {
                    heldDirection = direction;
                    applyStep(direction); // instant feedback for a plain tap
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
            return super.onKeyEvent(event);
        }
    }

    private void applyStep(int direction) {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
        } catch (Exception e) {
            android.util.Log.e("VolumeKeyAccessibilityService", "applyStep failed", e);
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
