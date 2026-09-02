package com.fuzz.volumehu;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.function.IntConsumer;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.fuzz.volumehu.widget.EqBarView;
import com.fuzz.volumehu.widget.ThemeColors;

/**
 * File:        VolumeOverlayService.java
 * Description: The whole floating widget - the always-on half-circle tab
 *              and the expandable panel (EQ-segments bar/ball, nudge arrow,
 *              2s-hold theme popup, collapse arrow). Runs as a foreground
 *              service with an ongoing notification so the OS won't kill it
 *              for memory pressure or a recents-list swipe; the only way to
 *              actually stop it is the long-press-to-close gesture on the
 *              tab (or force-stopping the app from Android's own settings -
 *              see AGENTS/README for why that ceiling is by OS design).
 *
 *              The readout always mirrors the real AudioManager volume
 *              as-is, even above 25 (e.g. changed from the head unit's own
 *              nav/media app) - only what THIS widget itself writes is ever
 *              capped at 25.
 * Author:      FuzzBC
 * Date:        2026-09-01
 */
public class VolumeOverlayService extends Service {

    private static final String CHANNEL_ID = "fuzz_volume_overlay";
    private static final int NOTIF_ID = 1001;

    private static final long LONGPRESS_MS = 650;
    private static final long TAP_MAX_MS = 350;
    private static final long THEME_HOLD_MS = 2000;
    private static final long NUDGE_COOLDOWN_MS = 500;

    // Size tab slider ranges (dp) - min value + seekbar's 0-based progress.
    private static final int BUBBLE_WIDTH_MIN_DP = 36, BUBBLE_WIDTH_MAX_DP = 90;
    private static final int PANEL_WIDTH_MIN_DP = 80, PANEL_WIDTH_MAX_DP = 260; // 80 = just enough for the 40dp EQ bar + panelCard's 14dp padding on each side
    // No fixed max here - the "Volume panel height" slider's ceiling is
    // computed live as 80% of the actual screen height (see
    // maxPanelBarHeightDp()) so it never lets the bar grow taller than the
    // screen can sensibly show, on any device.
    private static final int PANEL_BAR_HEIGHT_MIN_DP = 90;
    private static final float PANEL_BAR_HEIGHT_MAX_SCREEN_FRACTION = 0.8f;
    // The floating bubble's original proportions (52x108dp) and icon size
    // (see overlay_tab.xml history) as ratios, so resizing the bubble via
    // the Size tab scales its height and icon together instead of just its
    // width. TAB_ICON_RATIO is a little bigger than the bubble's original
    // 22/52 icon-to-width ratio.
    private static final float TAB_ASPECT = 108f / 52f;
    private static final float TAB_ICON_RATIO = 0.40f; // was ~0.34 (17.6/52) - "a little larger"
    // The settings popup card's own size is fixed - only the bubble and
    // the volume panel are user-resizable (Size tab).
    private static final int SETTINGS_POPUP_DIAMETER_DP = 260;
    // Conf tab slider range (volume units) for "max volume supported" - the
    // other two tiers ("limited to", "when go slowly") are bounded by
    // whichever tier sits directly above them instead of a fixed range.
    private static final int MAX_SUPPORTED_MIN = 10, MAX_SUPPORTED_MAX = 100;

    public static void start(Context ctx) {
        ContextCompat.startForegroundService(ctx, new Intent(ctx, VolumeOverlayService.class));
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, VolumeOverlayService.class));
    }

    private WindowManager wm;
    private AudioManager audioManager;
    private Prefs prefs;
    private Context themedCtx;
    private Handler mainHandler;
    private int touchSlop;

    private String side;
    private float vpos;
    private int themeIndex;
    private boolean dynamicColor;      // Theme tab: color follows volume vs. flat merge color
    private int bubbleWidthDp;         // Size tab - floating bubble's own size (height/icon scale with it)
    private int panelWidthDp;          // Size tab
    private int panelBarHeightDp;      // Size tab
    private int maxVolumeSupported;    // Conf tab: EQ bar's full-scale top
    private int widgetMax;             // Conf tab: "limited to" - this widget's write ceiling
    private int dragCap;               // Conf tab: "when go slowly" - direct-drag ceiling

    private View tabRoot;
    private WindowManager.LayoutParams tabParams;
    private boolean tabAdded = false;

    private View panelRoot;
    private WindowManager.LayoutParams panelParams;
    private boolean panelAdded = false;

    // Invisible full-screen tap-catcher, added behind the panel while it's
    // open so a tap anywhere outside it (unlike the theme popup's backdrop,
    // never dimmed - the panel sits over whatever app the user was already
    // in) collapses the panel back to the bubble, same as the collapse
    // arrow. The panel window itself sits on top and keeps handling its
    // own touches as always.
    private View panelBackdropRoot;
    private WindowManager.LayoutParams panelBackdropParams;
    private boolean panelBackdropAdded = false;

    private TextView volNum;
    private TextView volMax;
    private View holdProgressFill;
    private ImageButton nudgeBtn;
    private ImageButton collapseBtn;
    private EqBarView eqBar;
    private View readoutRow;

    // The settings popup is its own top-level overlay window (see
    // openPanel() vs showThemePopup()) so it can sit centered on the whole
    // screen instead of being squeezed inside the docked side panel's
    // width - and, unlike the panel, it's freely draggable (dragHandle) so
    // it never has to stay centered if that's in the way. A separate
    // full-screen backdrop window sits behind it purely to dim the screen
    // and soak up outside taps.
    private View themeBackdropRoot;
    private WindowManager.LayoutParams themeBackdropParams;
    private boolean themeBackdropAdded = false;

    private View themePopupRoot;
    private WindowManager.LayoutParams themePopupParams;
    private boolean themePopupAdded = false;
    private GridLayout themeGrid;
    private TextView themeCurrent;
    private final View[] themeSwatches = new View[ThemeColors.THEMES.length];
    private CheckBox dynamicCheck;

    // Popup tabs
    private TextView tabTheme, tabSize, tabConf;
    private View themeTabContent, sizeTabContent, confTabContent;

    // Size tab
    private TextView bubbleSizeLabel, panelWidthLabel, panelHeightLabel;
    private SeekBar bubbleSizeSeek, panelWidthSeek, panelHeightSeek;

    // Conf tab
    private TextView confMaxLabel, confLimitLabel, confSlowLabel, confDeviceMaxHint;
    private SeekBar confMaxSeek, confLimitSeek, confSlowSeek;

    // Settings popup drag handle
    private float dragHandleDownRawX, dragHandleDownRawY;
    private int dragHandleStartX, dragHandleStartY;

    private android.animation.ObjectAnimator holdAnim;
    private boolean nudgeLocked = false;

    private BroadcastReceiver volumeReceiver;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        // The WHOLE method is inside one try/catch now, not just the back
        // half - VolumeOverlayService.start() in MainActivity only guards
        // the *request* to start (startForegroundService() returning), not
        // what happens once the OS actually dispatches onCreate() here a
        // moment later - that dispatch runs outside any try/catch a caller
        // could wrap around start(). Everything that can fail must be caught
        // in here, not out there.
        TraceLog.step(this, "VolumeOverlayService.onCreate start");
        try {
            wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            TraceLog.step(this, "got WindowManager");
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            TraceLog.step(this, "got AudioManager");
            prefs = new Prefs(this);
            TraceLog.step(this, "created Prefs");
            themedCtx = new ContextThemeWrapper(this, R.style.AppTheme);
            TraceLog.step(this, "created themedCtx");
            mainHandler = new Handler(Looper.getMainLooper());
            touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
            TraceLog.step(this, "created Handler + touchSlop");

            side = prefs.getSide();
            vpos = prefs.getVpos();
            themeIndex = prefs.getTheme();
            dynamicColor = prefs.isDynamicColor();
            bubbleWidthDp = prefs.getBubbleWidthDp();
            panelWidthDp = prefs.getPanelWidthDp();
            panelBarHeightDp = Math.min(prefs.getPanelBarHeightDp(), maxPanelBarHeightDp());
            // Defensive clamp on load - Conf tab's tiers must stay ordered
            // (maxVolumeSupported >= widgetMax >= dragCap) even if a future
            // change to the defaults ever left a stale combination behind.
            maxVolumeSupported = Math.max(1, prefs.getMaxVolumeSupported());
            widgetMax = clampInt(prefs.getWidgetMax(), 1, maxVolumeSupported);
            dragCap = clampInt(prefs.getDragCap(), 0, widgetMax);
            TraceLog.step(this, "read prefs: side=" + side + " vpos=" + vpos + " theme=" + themeIndex
                    + " dynamic=" + dynamicColor + " maxSupported=" + maxVolumeSupported
                    + " widgetMax=" + widgetMax + " dragCap=" + dragCap);

            createNotificationChannel();
            TraceLog.step(this, "created notification channel");
            startForeground(NOTIF_ID, buildNotification());
            TraceLog.step(this, "startForeground OK");
            prefs.setOverlayStarted(true);

            registerVolumeReceiver();
            TraceLog.step(this, "registered volume receiver");
            buildTabView();
            TraceLog.step(this, "built tab view");
            addTabWindow();
            TraceLog.step(this, "added tab window - tabAdded=" + tabAdded);
            refreshVisuals();
            TraceLog.step(this, "onCreate SUCCESS");
        } catch (Throwable t) {
            // Throwable, not Exception: on unusual firmware a class-loading
            // or resource problem can surface as an Error, which a plain
            // catch (Exception) does not catch - and an uncaught one here
            // takes the whole process down, MainActivity included, since
            // they share a process. Never let a WindowManager/notification/
            // resource quirk do that - stop cleanly instead.
            android.util.Log.e("VolumeOverlayService", "startup failed, stopping", t);
            TraceLog.error(this, "VolumeOverlayService.onCreate FAILED", t);
            try { if (prefs != null) prefs.setOverlayStarted(false); } catch (Exception ignored) {}
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // NOT START_STICKY on purpose: that tells the OS to relaunch this
        // service on its own the moment it's killed - including by a crash.
        // If startup crashes for any reason, that turns into an infinite
        // restart-crash loop with no way for the user (or MainActivity) to
        // ever get in front of it. A fresh start now always comes from an
        // explicit source instead - MainActivity, BootReceiver - each of
        // which checks Prefs.wasOverlayStarted() and backs off after a crash
        // (see FuzzVolumeApp).
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        // Fully defensive, same reasoning as onCreate()'s own try/catch:
        // this runs both for the deliberate "Stop volume overlay" path AND
        // as the tail end of onCreate()'s own catch-and-stopSelf() on a
        // startup failure - which can happen before `prefs` (or even `wm`)
        // was ever assigned. Every field here used to be dereferenced
        // unguarded; a sufficiently early onCreate() failure would have
        // NPE'd right here, in a method the Service framework itself
        // doesn't wrap in any try/catch - an uncaught exception in
        // onDestroy() takes the whole process down same as anywhere else.
        super.onDestroy();
        try { persistSizeAndConfPrefsNow(); } catch (Exception ignored) {} // flush any pending Size/Conf slider edits before this instance is gone
        try { if (prefs != null) prefs.setOverlayStarted(false); } catch (Exception ignored) {}
        try { if (volumeReceiver != null) unregisterReceiver(volumeReceiver); } catch (Exception ignored) {}
        try { if (mainHandler != null) mainHandler.removeCallbacksAndMessages(null); } catch (Exception ignored) {}
        try { removeTabWindow(); } catch (Exception ignored) {}
        try { if (panelAdded && wm != null) { wm.removeView(panelRoot); panelAdded = false; } } catch (Exception ignored) {}
        try { if (panelBackdropAdded && wm != null) { wm.removeView(panelBackdropRoot); panelBackdropAdded = false; } } catch (Exception ignored) {}
        try { if (themePopupAdded && wm != null) { wm.removeView(themePopupRoot); themePopupAdded = false; } } catch (Exception ignored) {}
        try { if (themeBackdropAdded && wm != null) { wm.removeView(themeBackdropRoot); themeBackdropAdded = false; } } catch (Exception ignored) {}
        try { stopForeground(true); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------- Notification

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private android.app.Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, flags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_speaker)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .build();
    }

    // ---------------------------------------------------------- Volume sync

    private void registerVolumeReceiver() {
        volumeReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { refreshVisuals(); }
        };
        IntentFilter f = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(volumeReceiver, f);
        }
    }

    private int getRawVolume() { return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC); }
    private int getStreamMax() { return audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC); }

    /** Writes a new volume, always capped at this widget's own ceiling (widgetMax - Conf tab's "limited to"). */
    private void setRealVolume(int desired) {
        try {
            int clamped = Math.max(0, Math.min(Math.min(desired, widgetMax), getStreamMax()));
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0);
        } catch (Exception e) {
            android.util.Log.e("VolumeOverlayService", "setStreamVolume failed", e);
        }
        refreshVisuals();
    }

    // ---------------------------------------------------------- Tab window

    private void buildTabView() {
        tabRoot = LayoutInflater.from(themedCtx).inflate(R.layout.overlay_tab, null);
        tabRoot.setOnTouchListener(this::onTabTouch);
    }

    private WindowManager.LayoutParams newOverlayParams(int w, int h) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                w, h, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        return p;
    }

    private int bubbleHeightDp() { return Math.round(bubbleWidthDp * TAB_ASPECT); }

    private void addTabWindow() {
        if (tabAdded) return;
        try {
            tabParams = newOverlayParams(dp(bubbleWidthDp), dp(bubbleHeightDp()));
            positionTab();
            wm.addView(tabRoot, tabParams);
            tabAdded = true;
        } catch (Exception e) {
            android.util.Log.e("VolumeOverlayService", "addTabWindow failed, stopping", e);
            TraceLog.error(this, "addTabWindow FAILED (wm.addView)", e);
            stopSelf(); // nothing left to show - don't linger as a foreground service with no UI
        }
    }

    private void removeTabWindow() {
        if (!tabAdded) return;
        try { wm.removeView(tabRoot); } catch (Exception ignored) {}
        tabAdded = false;
    }

    private void positionTab() {
        Point sz = screenSize();
        int tabW = dp(bubbleWidthDp), tabH = dp(bubbleHeightDp());
        int x = "left".equals(side) ? 0 : sz.x - tabW;
        int y = Math.round((vpos / 100f) * sz.y - tabH / 2f);
        y = clampInt(y, 0, sz.y - tabH);
        tabParams.x = x;
        tabParams.y = y;
        tabParams.width = tabW;   // Size tab's bubble-size slider needs the WINDOW itself resized (see positionPanel())
        tabParams.height = tabH;
        if (tabAdded) wm.updateViewLayout(tabRoot, tabParams);
    }

    private boolean onTabTouch(View v, MotionEvent event) {
        try {
            return onTabTouchInner(event);
        } catch (Exception e) {
            android.util.Log.e("VolumeOverlayService", "onTabTouch failed", e);
            return true;
        }
    }

    private boolean onTabTouchInner(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                tabDownRawX = event.getRawX();
                tabDownRawY = event.getRawY();
                tabDownVpos = vpos;
                tabMoved = false;
                tabDownTime = SystemClock.elapsedRealtime();
                tabRoot.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start();
                mainHandler.postDelayed(longPressRunnable, LONGPRESS_MS);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getRawX() - tabDownRawX, dy = event.getRawY() - tabDownRawY;
                if (!tabMoved && Math.hypot(dx, dy) > touchSlop) {
                    tabMoved = true;
                    mainHandler.removeCallbacks(longPressRunnable);
                }
                if (tabMoved) {
                    Point sz = screenSize();
                    vpos = clampFloat(tabDownVpos + (dy / sz.y) * 100f, 10f, 90f);
                    String newSide = event.getRawX() > sz.x / 2f ? "right" : "left";
                    boolean sideChanged = !newSide.equals(side);
                    side = newSide;
                    positionTab();
                    // positionTab() only moves/resizes the window - the half-circle
                    // shape itself (flat edge vs rounded edge) and the icon's side
                    // alignment come from updateTabAppearance(), which only runs
                    // inside refreshVisuals(). Without this, dragging across the
                    // midpoint moved the tab but left its old shape/icon position
                    // stuck until something unrelated (a volume change) refreshed it.
                    if (sideChanged) refreshVisuals();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                mainHandler.removeCallbacks(longPressRunnable);
                tabRoot.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                long dt = SystemClock.elapsedRealtime() - tabDownTime;
                if (!tabMoved && dt < TAP_MAX_MS) {
                    openPanel();
                } else if (tabMoved) {
                    prefs.setSide(side);
                    prefs.setVpos(vpos);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                mainHandler.removeCallbacks(longPressRunnable);
                tabRoot.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                return true;
            }
        }
        return false;
    }

    private float tabDownRawX, tabDownRawY, tabDownVpos;
    private boolean tabMoved;
    private long tabDownTime;
    private final Runnable longPressRunnable = this::stopSelf; // real close - stops the foreground service

    // ---------------------------------------------------------- Panel window

    private void openPanel() {
        removeTabWindow();
        try {
            addPanelBackdrop(); // added first so panelRoot (added next) draws on top and keeps its own touches
            if (panelRoot == null) inflatePanel();
            panelParams = newOverlayParams(dp(panelWidthDp), WindowManager.LayoutParams.WRAP_CONTENT);
            positionPanel();
            wm.addView(panelRoot, panelParams);
            panelAdded = true;
            refreshVisuals();
        } catch (Exception e) {
            android.util.Log.e("VolumeOverlayService", "openPanel failed", e);
            removePanelBackdrop();
            addTabWindow(); // fall back to just the tab rather than leaving nothing on screen
        }
    }

    private void closePanel() {
        hideThemePopup();
        try {
            if (panelAdded) {
                wm.removeView(panelRoot);
                panelAdded = false;
            }
        } catch (Exception ignored) {}
        removePanelBackdrop();
        addTabWindow();
    }

    /** Invisible full-screen window behind the panel - a tap anywhere on it
     *  (i.e. outside the panel itself) collapses the panel. */
    private void addPanelBackdrop() {
        if (panelBackdropAdded) return;
        if (panelBackdropRoot == null) {
            panelBackdropRoot = new View(themedCtx);
            panelBackdropRoot.setOnClickListener(v -> closePanel());
        }
        panelBackdropParams = newOverlayParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        wm.addView(panelBackdropRoot, panelBackdropParams);
        panelBackdropAdded = true;
    }

    private void removePanelBackdrop() {
        if (!panelBackdropAdded) return;
        try { wm.removeView(panelBackdropRoot); } catch (Exception ignored) {}
        panelBackdropAdded = false;
    }

    private void positionPanel() {
        Point sz = screenSize();
        int panelW = dp(panelWidthDp);
        panelRoot.measure(
                View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(sz.y, View.MeasureSpec.AT_MOST));
        int panelH = panelRoot.getMeasuredHeight();
        int x = "left".equals(side) ? 0 : sz.x - panelW;
        int tabCenterY = Math.round((vpos / 100f) * sz.y);
        int minY = Math.round(sz.y * 0.06f);
        int maxY = Math.round(sz.y * 0.94f) - panelH;
        int y = clampInt(tabCenterY - panelH / 2, minY, Math.max(minY, maxY));
        panelParams.x = x;
        panelParams.y = y;
        panelParams.width = panelW; // the Size tab's width slider needs the WINDOW itself resized, not just re-measured
        if (panelAdded) wm.updateViewLayout(panelRoot, panelParams);
    }

    private void inflatePanel() {
        panelRoot = LayoutInflater.from(themedCtx).inflate(R.layout.overlay_panel, null);
        readoutRow = panelRoot.findViewById(R.id.readoutRow);
        volNum = panelRoot.findViewById(R.id.volNum);
        volMax = panelRoot.findViewById(R.id.volMax);
        holdProgressFill = panelRoot.findViewById(R.id.holdProgressFill);
        holdProgressFill.setPivotX(0f);
        nudgeBtn = panelRoot.findViewById(R.id.nudgeBtn);
        collapseBtn = panelRoot.findViewById(R.id.collapseBtn);
        eqBar = panelRoot.findViewById(R.id.eqBar);
        applyBarHeightLive();
        eqBar.setVolMax(maxVolumeSupported);
        eqBar.setDragCap(dragCap);

        readoutRow.setOnTouchListener(this::onThemeHoldTouch);
        collapseBtn.setOnClickListener(v -> closePanel());
        nudgeBtn.setOnClickListener(v -> onNudgeClick());
        eqBar.setListener(new EqBarView.Listener() {
            @Override public void onDragValue(int value0toMax) { setRealVolume(value0toMax); }
            @Override public void onDragEnd() { /* already applied live */ }
        });
    }

    /** Applies panelBarHeightDp (Size tab) to the already-inflated EQ bar,
     *  clamped to maxPanelBarHeightDp() defensively - the Size tab's own
     *  slider already can't exceed it, but this is the one place that
     *  actually touches the live view, so it's the right last line of
     *  defense against a stale/larger value from Prefs (e.g. set on a
     *  taller screen, then reused on a shorter one). */
    private void applyBarHeightLive() {
        if (eqBar == null) return;
        ViewGroup.LayoutParams lp = eqBar.getLayoutParams();
        if (lp == null) return;
        lp.height = dp(Math.min(panelBarHeightDp, maxPanelBarHeightDp()));
        eqBar.setLayoutParams(lp);
    }

    /** 80% of the current screen height, in dp - the "Volume panel height"
     *  slider's ceiling, so the EQ bar can never grow taller than the
     *  screen can sensibly show on any device. */
    private int maxPanelBarHeightDp() {
        Point sz = screenSize();
        float density = getResources().getDisplayMetrics().density;
        int screenHeightDp = Math.round(sz.y / density);
        return Math.max(PANEL_BAR_HEIGHT_MIN_DP, Math.round(screenHeightDp * PANEL_BAR_HEIGHT_MAX_SCREEN_FRACTION));
    }

    /** Inflates the settings popup as its own top-level overlay window,
     *  separate from the docked side panel, so it can sit centered on the
     *  whole screen (see showThemePopup()) - and be freely dragged anywhere
     *  after that (dragHandle) - instead of being squeezed into the panel's
     *  own width. Three tabs: Theme, Size, Conf. */
    private void inflateThemePopup() {
        themePopupRoot = LayoutInflater.from(themedCtx).inflate(R.layout.overlay_theme_popup, null);
        themeGrid = themePopupRoot.findViewById(R.id.themeGrid);
        themeCurrent = themePopupRoot.findViewById(R.id.themeCurrent);
        Button themeDone = themePopupRoot.findViewById(R.id.themeDone);
        ImageButton themeClose = themePopupRoot.findViewById(R.id.themeClose);
        ImageButton dragHandle = themePopupRoot.findViewById(R.id.dragHandle);
        dynamicCheck = themePopupRoot.findViewById(R.id.dynamicCheck);

        tabTheme = themePopupRoot.findViewById(R.id.tabTheme);
        tabSize = themePopupRoot.findViewById(R.id.tabSize);
        tabConf = themePopupRoot.findViewById(R.id.tabConf);
        themeTabContent = themePopupRoot.findViewById(R.id.themeTabContent);
        sizeTabContent = themePopupRoot.findViewById(R.id.sizeTabContent);
        confTabContent = themePopupRoot.findViewById(R.id.confTabContent);

        bubbleSizeLabel = themePopupRoot.findViewById(R.id.bubbleSizeLabel);
        panelWidthLabel = themePopupRoot.findViewById(R.id.panelWidthLabel);
        panelHeightLabel = themePopupRoot.findViewById(R.id.panelHeightLabel);
        bubbleSizeSeek = themePopupRoot.findViewById(R.id.bubbleSizeSeek);
        panelWidthSeek = themePopupRoot.findViewById(R.id.panelWidthSeek);
        panelHeightSeek = themePopupRoot.findViewById(R.id.panelHeightSeek);

        confMaxLabel = themePopupRoot.findViewById(R.id.confMaxLabel);
        confLimitLabel = themePopupRoot.findViewById(R.id.confLimitLabel);
        confSlowLabel = themePopupRoot.findViewById(R.id.confSlowLabel);
        confDeviceMaxHint = themePopupRoot.findViewById(R.id.confDeviceMaxHint);
        confMaxSeek = themePopupRoot.findViewById(R.id.confMaxSeek);
        confLimitSeek = themePopupRoot.findViewById(R.id.confLimitSeek);
        confSlowSeek = themePopupRoot.findViewById(R.id.confSlowSeek);

        // This device's own real ceiling for media volume - Android/the
        // OEM sets this (e.g. many Samsung phones cap STREAM_MUSIC at 15
        // steps), and it's a hard limit no app can write past, completely
        // independent of "max volume supported" above. Shown once so a
        // configured 40/25/20 that's silently capped lower on THIS device
        // doesn't look like the app ignoring its own settings.
        try {
            confDeviceMaxHint.setText("This device's own real limit: " + getStreamMax());
        } catch (Exception ignored) {}

        themeDone.setOnClickListener(v -> hideThemePopup());
        themeClose.setOnClickListener(v -> hideThemePopup());
        dragHandle.setOnTouchListener(this::onDragHandleTouch);

        tabTheme.setOnClickListener(v -> selectSettingsTab(0));
        tabSize.setOnClickListener(v -> selectSettingsTab(1));
        tabConf.setOnClickListener(v -> selectSettingsTab(2));

        dynamicCheck.setChecked(dynamicColor);
        dynamicCheck.setOnCheckedChangeListener((btn, checked) -> {
            dynamicColor = checked;
            prefs.setDynamicColor(checked);
            refreshVisuals();
        });

        populateThemeGrid();
        wireSizeTab();
        wireConfTab();
    }

    private void selectSettingsTab(int index) {
        themeTabContent.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        sizeTabContent.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        confTabContent.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        styleSettingsTab(tabTheme, index == 0);
        styleSettingsTab(tabSize, index == 1);
        styleSettingsTab(tabConf, index == 2);
        // Each tab's content is a different height - re-layout the window
        // now that tabContent's measured height has changed.
        if (themePopupAdded) wm.updateViewLayout(themePopupRoot, themePopupParams);
    }

    private void styleSettingsTab(TextView tab, boolean selected) {
        tab.setBackgroundResource(selected ? R.drawable.bg_done_button : R.drawable.bg_small_button);
        tab.setTextColor(selected ? ContextCompat.getColor(this, R.color.cream) : Color.parseColor("#8A7A5C"));
    }

    // ---------------------------------------------------------- Size tab

    private void wireSizeTab() {
        bubbleSizeSeek.setMax(BUBBLE_WIDTH_MAX_DP - BUBBLE_WIDTH_MIN_DP);
        panelWidthSeek.setMax(PANEL_WIDTH_MAX_DP - PANEL_WIDTH_MIN_DP);
        panelHeightSeek.setMax(Math.max(1, maxPanelBarHeightDp() - PANEL_BAR_HEIGHT_MIN_DP));
        syncSizeTabUI();

        onSeek(bubbleSizeSeek, v -> {
            bubbleWidthDp = BUBBLE_WIDTH_MIN_DP + v;
            bubbleSizeLabel.setText("Bubble size: " + bubbleWidthDp + "dp");
            applyBubbleSizeLive();
        });
        onSeek(panelWidthSeek, v -> {
            panelWidthDp = PANEL_WIDTH_MIN_DP + v;
            panelWidthLabel.setText("Volume panel width: " + panelWidthDp + "dp");
            if (panelAdded) positionPanel();
        });
        onSeek(panelHeightSeek, v -> {
            panelBarHeightDp = Math.min(PANEL_BAR_HEIGHT_MIN_DP + v, maxPanelBarHeightDp());
            panelHeightLabel.setText("Volume panel height: " + panelBarHeightDp + "dp");
            applyBarHeightLive();
            if (panelAdded) positionPanel();
        });
    }

    private void syncSizeTabUI() {
        bubbleSizeSeek.setProgress(bubbleWidthDp - BUBBLE_WIDTH_MIN_DP);
        bubbleSizeLabel.setText("Bubble size: " + bubbleWidthDp + "dp");
        panelWidthSeek.setProgress(panelWidthDp - PANEL_WIDTH_MIN_DP);
        panelWidthLabel.setText("Volume panel width: " + panelWidthDp + "dp");
        panelHeightSeek.setMax(Math.max(1, maxPanelBarHeightDp() - PANEL_BAR_HEIGHT_MIN_DP));
        panelHeightSeek.setProgress(panelBarHeightDp - PANEL_BAR_HEIGHT_MIN_DP);
        panelHeightLabel.setText("Volume panel height: " + panelBarHeightDp + "dp");
    }

    /** Live-resizes the floating bubble (and its icon, via refreshVisuals()
     *  -> updateTabAppearance()) as the Size tab's slider moves. The tab
     *  window is usually hidden while this popup is open (the panel took
     *  its place), so the resize itself is applied to tabParams right away
     *  but the visual effect on the tab only appears once the panel closes
     *  and addTabWindow() rebuilds it - positionTab() is still called
     *  defensively in case a future path has the tab and popup up together. */
    private void applyBubbleSizeLive() {
        positionTab();
        refreshVisuals();
    }

    // ---------------------------------------------------------- Conf tab

    private void wireConfTab() {
        confMaxSeek.setMax(MAX_SUPPORTED_MAX - MAX_SUPPORTED_MIN);
        syncConfTabUI();

        // Each slider only ever nudges the OTHER sliders' bounds/progress,
        // never its own mid-drag - resetting a seekbar's own progress while
        // the user's finger is still on it makes the touch jump/stutter.
        onSeek(confMaxSeek, v -> {
            maxVolumeSupported = MAX_SUPPORTED_MIN + v;
            confMaxLabel.setText("Max volume supported: " + maxVolumeSupported);

            confLimitSeek.setMax(Math.max(1, maxVolumeSupported - 1));
            if (widgetMax > maxVolumeSupported) widgetMax = maxVolumeSupported;
            confLimitSeek.setProgress(widgetMax - 1);
            confLimitLabel.setText("Limited to: " + widgetMax);

            confSlowSeek.setMax(widgetMax);
            if (dragCap > widgetMax) dragCap = widgetMax;
            confSlowSeek.setProgress(dragCap);
            confSlowLabel.setText("When go slowly: " + dragCap);

            applyConfLive();
        });
        onSeek(confLimitSeek, v -> {
            widgetMax = clampInt(v + 1, 1, maxVolumeSupported);
            confLimitLabel.setText("Limited to: " + widgetMax);

            confSlowSeek.setMax(widgetMax);
            if (dragCap > widgetMax) dragCap = widgetMax;
            confSlowSeek.setProgress(dragCap);
            confSlowLabel.setText("When go slowly: " + dragCap);

            applyConfLive();
        });
        onSeek(confSlowSeek, v -> {
            dragCap = clampInt(v, 0, widgetMax);
            confSlowLabel.setText("When go slowly: " + dragCap);
            applyConfLive();
        });
    }

    private void syncConfTabUI() {
        confMaxSeek.setProgress(maxVolumeSupported - MAX_SUPPORTED_MIN);
        confMaxLabel.setText("Max volume supported: " + maxVolumeSupported);
        confLimitSeek.setMax(Math.max(1, maxVolumeSupported - 1));
        confLimitSeek.setProgress(widgetMax - 1);
        confLimitLabel.setText("Limited to: " + widgetMax);
        confSlowSeek.setMax(widgetMax);
        confSlowSeek.setProgress(dragCap);
        confSlowLabel.setText("When go slowly: " + dragCap);
    }

    /** Pushes maxVolumeSupported/dragCap into the live EQ bar and re-renders
     *  everything else that depends on the volume tiers (nudge visibility,
     *  dynamic color fraction). */
    private void applyConfLive() {
        if (eqBar != null) {
            eqBar.setVolMax(maxVolumeSupported);
            eqBar.setDragCap(dragCap);
        }
        refreshVisuals();
    }

    /** Small SeekBar.OnSeekBarChangeListener helper - only user-driven
     *  changes matter here (programmatic setProgress() calls, e.g. from
     *  syncSizeTabUI()/syncConfTabUI(), must not re-trigger side effects).
     *  onChange only updates in-memory fields and live visuals on every
     *  tick, deliberately NOT Prefs - see persistSizeAndConfPrefsNow(),
     *  called once here on release (onStopTrackingTouch) instead. Writing
     *  to Prefs on every tick during a drag would mean dozens of disk
     *  writes per second; deferring to release keeps dragging smooth
     *  while still guaranteeing the final value is committed to disk
     *  (synchronously - see Prefs.java) before the user can move on. */
    private void onSeek(SeekBar sb, IntConsumer onChange) {
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) onChange.accept(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { persistSizeAndConfPrefsNow(); }
        });
    }

    /** Writes every Size/Conf tab value currently in memory to Prefs with a
     *  synchronous commit() (not the usual async apply()) - called once a
     *  slider drag actually ends (onSeek's onStopTrackingTouch) and again
     *  as a safety net whenever the settings popup closes, so a value is
     *  never left only in memory if this device's OEM battery/security
     *  manager kills the process right after (a real, previously reported
     *  risk on this app's target head units - see CHANGELOG history). */
    private void persistSizeAndConfPrefsNow() {
        try {
            prefs.setBubbleWidthDp(bubbleWidthDp);
            prefs.setPanelWidthDp(panelWidthDp);
            prefs.setPanelBarHeightDp(panelBarHeightDp);
            prefs.setMaxVolumeSupported(maxVolumeSupported);
            prefs.setWidgetMax(widgetMax);
            prefs.setDragCap(dragCap);
        } catch (Exception e) {
            android.util.Log.e("VolumeOverlayService", "persistSizeAndConfPrefsNow failed", e);
        }
    }

    // ---------------------------------------------------------- Settings popup drag

    private boolean onDragHandleTouch(View v, MotionEvent event) {
        try {
            return onDragHandleTouchInner(event);
        } catch (Exception e) {
            android.util.Log.e("VolumeOverlayService", "onDragHandleTouch failed", e);
            return true;
        }
    }

    private boolean onDragHandleTouchInner(MotionEvent event) {
        if (themePopupParams == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragHandleDownRawX = event.getRawX();
                dragHandleDownRawY = event.getRawY();
                dragHandleStartX = themePopupParams.x;
                dragHandleStartY = themePopupParams.y;
                return true;
            case MotionEvent.ACTION_MOVE: {
                Point sz = screenSize();
                int dx = Math.round(event.getRawX() - dragHandleDownRawX);
                int dy = Math.round(event.getRawY() - dragHandleDownRawY);
                int popupH = themePopupRoot.getHeight() > 0 ? themePopupRoot.getHeight() : dp(SETTINGS_POPUP_DIAMETER_DP);
                int newX = clampInt(dragHandleStartX + dx, 0, Math.max(0, sz.x - themePopupParams.width));
                int newY = clampInt(dragHandleStartY + dy, 0, Math.max(0, sz.y - popupH));
                themePopupParams.x = newX;
                themePopupParams.y = newY;
                if (themePopupAdded) wm.updateViewLayout(themePopupRoot, themePopupParams);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                prefs.setPopupPos(themePopupParams.x, themePopupParams.y);
                return true;
        }
        return false;
    }

    private void onNudgeClick() {
        try {
            if (nudgeLocked) return;
            int raw = getRawVolume();
            if (raw >= widgetMax) return;
            setRealVolume(raw + 1);
            nudgeLocked = true;
            nudgeBtn.animate().scaleX(1.3f).scaleY(1.3f).setDuration(140)
                    .withEndAction(() -> nudgeBtn.animate().scaleX(1f).scaleY(1f).setDuration(160).start())
                    .start();
            mainHandler.postDelayed(() -> nudgeLocked = false, NUDGE_COOLDOWN_MS);
        } catch (Exception e) {
            android.util.Log.e("VolumeOverlayService", "onNudgeClick failed", e);
        }
    }

    // ---------------------------------------------------------- 2s hold -> theme popup

    private float themeDownX, themeDownY;
    private boolean themeMoved;
    private final Runnable themeHoldRunnable = this::showThemePopup;

    private boolean onThemeHoldTouch(View v, MotionEvent event) {
        try {
            return onThemeHoldTouchInner(event);
        } catch (Exception e) {
            android.util.Log.e("VolumeOverlayService", "onThemeHoldTouch failed", e);
            return true;
        }
    }

    private boolean onThemeHoldTouchInner(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                themeDownX = event.getRawX();
                themeDownY = event.getRawY();
                themeMoved = false;
                startHoldProgress();
                mainHandler.postDelayed(themeHoldRunnable, THEME_HOLD_MS);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!themeMoved && Math.hypot(event.getRawX() - themeDownX, event.getRawY() - themeDownY) > touchSlop) {
                    themeMoved = true;
                    mainHandler.removeCallbacks(themeHoldRunnable);
                    cancelHoldProgress();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mainHandler.removeCallbacks(themeHoldRunnable);
                cancelHoldProgress();
                return true;
        }
        return false;
    }

    private void startHoldProgress() {
        cancelHoldProgress();
        holdAnim = android.animation.ObjectAnimator.ofFloat(holdProgressFill, "scaleX", 0f, 1f);
        holdAnim.setDuration(THEME_HOLD_MS);
        holdAnim.start();
    }

    private void cancelHoldProgress() {
        if (holdAnim != null) holdAnim.cancel();
        if (holdProgressFill != null) holdProgressFill.setScaleX(0f);
    }

    private void showThemePopup() {
        try {
            if (themePopupAdded) return;
            if (themeBackdropRoot == null) {
                themeBackdropRoot = LayoutInflater.from(themedCtx).inflate(R.layout.overlay_theme_backdrop, null);
                themeBackdropRoot.setOnClickListener(v -> hideThemePopup()); // tap outside the card closes the popup
            }
            if (!themeBackdropAdded) {
                themeBackdropParams = newOverlayParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                wm.addView(themeBackdropRoot, themeBackdropParams);
                themeBackdropAdded = true;
            }

            if (themePopupRoot == null) inflateThemePopup();
            int diameterPx = dp(SETTINGS_POPUP_DIAMETER_DP);
            themePopupParams = newOverlayParams(diameterPx, WindowManager.LayoutParams.WRAP_CONTENT);

            Point sz = screenSize();
            themePopupRoot.measure(
                    View.MeasureSpec.makeMeasureSpec(diameterPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(sz.y, View.MeasureSpec.AT_MOST));
            int popupH = themePopupRoot.getMeasuredHeight();

            int savedX = prefs.getPopupX(), savedY = prefs.getPopupY();
            int x, y;
            if (savedX >= 0 && savedY >= 0) {
                // A remembered drag position - keep it on-screen even if the
                // popup's own size (Size tab) or the screen changed since.
                x = clampInt(savedX, 0, Math.max(0, sz.x - diameterPx));
                y = clampInt(savedY, 0, Math.max(0, sz.y - popupH));
            } else {
                x = (sz.x - diameterPx) / 2;
                y = (sz.y - popupH) / 2;
            }
            themePopupParams.x = x;
            themePopupParams.y = y;

            wm.addView(themePopupRoot, themePopupParams);
            themePopupAdded = true;
            refreshThemeGridSelection();
            syncSizeTabUI();
            syncConfTabUI();
        } catch (Exception e) {
            android.util.Log.e("VolumeOverlayService", "showThemePopup failed", e);
        }
    }

    private void hideThemePopup() {
        // Safety net alongside onSeek()'s onStopTrackingTouch: if the popup
        // is closed (Done/X/tap-outside) mid-drag, the SeekBar losing its
        // window isn't guaranteed to deliver a clean release event first -
        // make sure nothing adjusted this session is left only in memory.
        persistSizeAndConfPrefsNow();
        try {
            if (themePopupAdded) {
                wm.removeView(themePopupRoot);
                themePopupAdded = false;
            }
            if (themeBackdropAdded) {
                wm.removeView(themeBackdropRoot);
                themeBackdropAdded = false;
            }
        } catch (Exception e) {
            android.util.Log.e("VolumeOverlayService", "hideThemePopup failed", e);
        }
    }

    private void populateThemeGrid() {
        themeGrid.removeAllViews();
        ThemeColors.Theme[] themes = ThemeColors.THEMES;
        for (int i = 0; i < themes.length; i++) {
            final int idx = i;
            View item = LayoutInflater.from(themedCtx).inflate(R.layout.theme_swatch_item, themeGrid, false);
            View ball = item.findViewById(R.id.ball);
            TextView tname = item.findViewById(R.id.tname);
            tname.setText(themes[i].name);

            GradientDrawable bg = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{themes[i].low, themes[i].mid, themes[i].high});
            bg.setShape(GradientDrawable.OVAL);
            ball.setBackground(bg);

            item.setOnClickListener(v -> {
                try {
                    themeIndex = idx;
                    prefs.setTheme(idx);
                    refreshVisuals();
                } catch (Exception e) {
                    android.util.Log.e("VolumeOverlayService", "theme swatch click failed", e);
                }
            });
            themeSwatches[i] = item;
            themeGrid.addView(item);
        }
    }

    private void refreshThemeGridSelection() {
        for (int i = 0; i < themeSwatches.length; i++) {
            View ball = themeSwatches[i].findViewById(R.id.ball);
            Drawable d = ball.getBackground();
            if (d instanceof GradientDrawable) {
                boolean selected = i == themeIndex;
                ((GradientDrawable) d).setStroke(selected ? dp(3) : 0, Color.parseColor("#3A2F1C"));
            }
        }
        if (themeCurrent != null) themeCurrent.setText(ThemeColors.THEMES[themeIndex].name);
    }

    // ---------------------------------------------------------- Shared visual refresh

    // The most frequently-called shared method (every volume change, theme
    // pick, and external VOLUME_CHANGED_ACTION broadcast runs through here) -
    // wrapped as a single choke point instead of guarding every caller.
    private void refreshVisuals() {
        try {
            int raw = getRawVolume();
            int barVal = Math.max(0, Math.min(maxVolumeSupported, raw));
            int color = colorForCurrent(raw);

            updateTabAppearance(color);

            if (panelAdded && volNum != null) {
                volNum.setText(String.valueOf(raw));
                if (volMax != null) volMax.setText("/" + maxVolumeSupported);
                eqBar.setBarValue(barVal);
                eqBar.setBallColor(color);
                boolean showNudge = raw >= dragCap && raw < widgetMax;
                nudgeBtn.setVisibility(showNudge ? View.VISIBLE : View.INVISIBLE);
                collapseBtn.setRotation("right".equals(side) ? 90f : -90f);
                applyPanelTheme(color);
            }
            if (themePopupAdded) refreshThemeGridSelection();
        } catch (Exception e) {
            android.util.Log.e("VolumeOverlayService", "refreshVisuals failed", e);
        }
    }

    /** The color the whole widget shows for a given raw volume - Theme tab's
     *  Dynamic checkbox switches between two very different behaviors:
     *  checked, the color follows volume (the theme's own color sliding to
     *  red as it nears widgetMax, "limited to"); unchecked, one flat theme
     *  color everywhere regardless of volume ("merge with theme"). */
    private int colorForCurrent(int raw) {
        int idx = Math.max(0, Math.min(ThemeColors.THEMES.length - 1, themeIndex));
        int themeColor = ThemeColors.THEMES[idx].mid;
        if (!dynamicColor) return themeColor;
        float frac = widgetMax <= 0 ? 0f : Math.max(0f, Math.min(1f, raw / (float) widgetMax));
        return mixColors(themeColor, Color.parseColor("#DC2626"), frac);
    }

    private void updateTabAppearance(int volumeColor) {
        float r = dp(999);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(mixColors(Color.parseColor("#E6E2D8"), volumeColor, 0.4f));
        if ("left".equals(side)) {
            bg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        } else {
            bg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        }
        tabRoot.setBackground(bg);

        ImageView icon = tabRoot.findViewById(R.id.tabIcon);
        icon.setColorFilter(Color.parseColor("#77592A"));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) icon.getLayoutParams();
        int iconSize = dp(Math.round(bubbleWidthDp * TAB_ICON_RATIO));
        lp.width = iconSize;
        lp.height = iconSize;
        lp.gravity = Gravity.CENTER_VERTICAL | ("left".equals(side) ? Gravity.START : Gravity.END);
        lp.leftMargin = "left".equals(side) ? dp(8) : 0;
        lp.rightMargin = "right".equals(side) ? dp(8) : 0;
        icon.setLayoutParams(lp);
    }

    /** Re-skins the whole open panel (card, buttons, hold-progress fill) in
     *  the current theme's volume color, not just the EQ ball - card corner
     *  shape also flips to match whichever edge the panel is docked to,
     *  same "flat against the edge, rounded into the screen" logic as the
     *  tab's own shape. */
    private void applyPanelTheme(int color) {
        if (panelRoot == null) return;
        View panelCard = panelRoot.findViewById(R.id.panelCard);
        if (panelCard != null) {
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(mixColors(Color.parseColor("#E6E2D8"), color, 0.22f));
            cardBg.setCornerRadii(panelCornerRadii());
            panelCard.setBackground(cardBg);
        }
        tintSmallButton(nudgeBtn, color);
        tintSmallButton(collapseBtn, color);
        if (holdProgressFill != null) holdProgressFill.setBackgroundColor(color);
    }

    private void tintSmallButton(View btn, int color) {
        if (btn == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(mixColors(Color.parseColor("#DED9CC"), color, 0.35f));
        bg.setCornerRadius(dp(999)); // fully rounded (pill) - matches bg_small_button.xml
        btn.setBackground(bg);
    }

    private float[] panelCornerRadii() {
        float r = dp(18);
        return "left".equals(side)
                ? new float[]{0, 0, r, r, r, r, 0, 0}
                : new float[]{r, r, 0, 0, 0, 0, r, r};
    }

    // ---------------------------------------------------------- helpers

    private Point screenSize() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new Point(dm.widthPixels, dm.heightPixels);
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private static int clampInt(int v, int a, int b) { return Math.max(a, Math.min(b, v)); }
    private static float clampFloat(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }

    private static int mixColors(int a, int b, float t) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }
}
