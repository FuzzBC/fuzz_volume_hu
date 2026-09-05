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
import android.graphics.Paint;
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
import android.widget.LinearLayout;
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
    // Panel tab's "Hide after" slider range (seconds) - how long the panel
    // stays open with no interaction before auto-closing back to the bubble,
    // see scheduleAutoClosePanel()/panelHideSeconds.
    private static final int PANEL_HIDE_MIN_S = 2, PANEL_HIDE_MAX_S = 30;

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
    private static final float TAB_ICON_RATIO = 0.80f; // 80% of the bubble's own width - scales with it via updateTabAppearance()
    // The settings popup card's own size is fixed - only the bubble and
    // the volume panel are user-resizable (Size tab).
    private static final int SETTINGS_POPUP_DIAMETER_DP = 260;

    /** Panel/bubble background shapes - Form tab. Index 4 ("Clear") means no
     *  background drawable at all - see buildBubbleDrawable()/applyPanelTheme(). */
    private static final String[] BG_SHAPE_NAMES = {"Themed", "Rounded", "Square", "Pill", "Clear"};

    /** Bubble icon glyphs (Bubble tab), grouped into categories for the
     *  picker list - see populateBubbleList(). Index into both arrays is
     *  the value stored in Prefs.getBubbleIcon()/setBubbleIcon(). */
    private static final String[] ICON_NAMES = {
            "Classic", "Minimal", "Bold", "Hairline",                        // Classic
            "EQ Bars", "VU Meter", "Radar", "Waveform", "Pulse Dot", "Fade Waves", // Audio levels
            "Megaphone", "Headphones", "Volume Knob", "Geometric",           // Alternate
            "Retro LCD", "Wheel + Wave",                                     // Thematic
    };
    private static final int[] ICON_DRAWABLES = {
            R.drawable.ic_speaker, R.drawable.ic_speaker_minimal, R.drawable.ic_speaker_bold, R.drawable.ic_speaker_hairline,
            R.drawable.ic_speaker_eqbars, R.drawable.ic_speaker_vumeter, R.drawable.ic_speaker_radar,
            R.drawable.ic_speaker_waveform, R.drawable.ic_speaker_pulsedot, R.drawable.ic_speaker_fadewaves,
            R.drawable.ic_speaker_megaphone, R.drawable.ic_speaker_headphones, R.drawable.ic_speaker_knob, R.drawable.ic_speaker_geometric,
            R.drawable.ic_speaker_retrolcd, R.drawable.ic_speaker_wheelwave,
    };
    // Index in ICON_NAMES/ICON_DRAWABLES where each category starts, paired
    // with the header text populateBubbleList() inserts right before it.
    private static final int[] ICON_CATEGORY_STARTS = {0, 4, 10, 14};
    private static final String[] ICON_CATEGORY_NAMES = {"Icon - Classic", "Icon - Audio levels", "Icon - Alternate", "Icon - Thematic"};

    /** Sentinel theme index meaning "the Custom swatch, not ThemeColors.THEMES" -
     *  negative so it can never collide with a real array index. Its color
     *  lives in Prefs.getCustomColor()/customColor, not in THEMES. */
    private static final int CUSTOM_THEME_INDEX = -1;
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
    private boolean ledcarSyncEnabled; // Theme tab: "LEDCAR Set" - mirror LEDCAR's own color instead of the theme, highest priority when on
    private int ledcarColor = -1;      // last color received from LEDCAR (Color.rgb-encoded); -1 = none received yet this run
    private int customColor;           // Theme tab: the Custom swatch's own RGB - only meaningful while themeIndex == CUSTOM_THEME_INDEX
    private int formIndex;             // Form tab: index into EqBarView.FORM_NAMES
    private int panelBgShape;          // Form tab: index into BG_SHAPE_NAMES
    private int bubbleBgShape;         // Form tab: index into BG_SHAPE_NAMES
    private int bubbleIconIndex;       // Form tab: index into ICON_NAMES/ICON_DRAWABLES
    private int bubbleWidthDp;         // Size tab - floating bubble's own size (height/icon scale with it)
    private int panelWidthDp;          // Size tab
    private int panelBarHeightDp;      // Size tab
    private int panelHideSeconds;      // Panel tab - auto-close-to-bubble idle timer
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
    private View themeGridScroll; // the grid's own ScrollView - hidden while the Custom RGB panel is showing, see setCustomRgbPanelVisible()
    private TextView themeCurrent;
    private final View[] themeSwatches = new View[ThemeColors.THEMES.length];
    private View customSwatch; // the "Custom" swatch - lives in customSwatchSlot, not part of THEMES/themeGrid/themeSwatches
    private FrameLayout customSwatchSlot; // its own row above the preset grid - see populateThemeGrid()
    private CheckBox dynamicCheck;
    private CheckBox ledcarSyncCheck;

    // Custom theme's RGB slider panel - shown only while the Custom swatch is selected
    private View customRgbPanel;
    private SeekBar customRSeek, customGSeek, customBSeek;
    private TextView customRgbHex;
    private View customRgbPreview;
    private BroadcastReceiver ledcarColorReceiver;
    private boolean ledcarReceiverRegistered = false;

    // Popup tabs
    // Tab order (see selectSettingsTab()): 0 Theme, 1 Conf, 2 Bubble, 3 Panel -
    // Bubble/Panel replaced the old Size/Form split, which mixed bubble-only
    // and panel-only controls across both tabs with no clear organization.
    private TextView tabTheme, tabConf, tabBubble, tabPanel;
    private View themeTabContent, confTabContent, bubbleTabContent, panelTabContent;

    // Bubble tab: size + live preview (moved from the old Size tab), then
    // Bubble background and Bubble icon pickers (moved from the old Form tab).
    private LinearLayout bubbleList;
    private TextView bubbleSizeLabel;
    private final View[] bubbleBgRows = new View[BG_SHAPE_NAMES.length];
    private final View[] iconRows = new View[ICON_NAMES.length];

    // Panel tab: width/height (moved from the old Size tab), then Panel
    // style and Panel background pickers (moved from the old Form tab).
    private LinearLayout panelList;
    private TextView panelWidthLabel, panelHeightLabel, panelHideLabel;
    private final View[] formRows = new View[EqBarView.FORM_NAMES.length];
    private final View[] panelBgRows = new View[BG_SHAPE_NAMES.length];
    private SeekBar bubbleSizeSeek, panelWidthSeek, panelHeightSeek, panelHideSeek;

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
            ledcarSyncEnabled = prefs.isLedcarSync();
            ledcarColor = prefs.getLedcarLastColor();
            customColor = prefs.getCustomColor();
            formIndex = clampInt(prefs.getForm(), 0, EqBarView.FORM_NAMES.length - 1);
            panelBgShape = clampInt(prefs.getPanelBgShape(), 0, BG_SHAPE_NAMES.length - 1);
            bubbleBgShape = clampInt(prefs.getBubbleBgShape(), 0, BG_SHAPE_NAMES.length - 1);
            bubbleIconIndex = clampInt(prefs.getBubbleIcon(), 0, ICON_NAMES.length - 1);
            bubbleWidthDp = prefs.getBubbleWidthDp();
            panelWidthDp = prefs.getPanelWidthDp();
            panelBarHeightDp = Math.min(prefs.getPanelBarHeightDp(), maxPanelBarHeightDp());
            panelHideSeconds = clampInt(prefs.getPanelHideSeconds(), PANEL_HIDE_MIN_S, PANEL_HIDE_MAX_S);
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
            if (ledcarSyncEnabled) registerLedcarReceiver();
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
        try { unregisterLedcarReceiver(); } catch (Exception ignored) {}
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
            // Only reacts if the panel is already open (refreshVisuals()
            // itself no-ops the panel-only parts when !panelAdded) - a
            // volume change never opens or closes anything on its own.
            @Override public void onReceive(Context context, Intent intent) { refreshVisuals(); }
        };
        IntentFilter f = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(volumeReceiver, f);
        }
    }

    /** "LEDCAR Set" (Theme tab): listens for FuZz LEDCAR's own color-change
     *  broadcast (see that app's MainActivity.broadcastColorToExternalApps())
     *  and mirrors it here. Unlike volumeReceiver, this MUST be
     *  RECEIVER_EXPORTED on API 33+ - the sender is a genuinely different
     *  app/UID, not this app's own process talking to itself, so
     *  NOT_EXPORTED (which only allows same-app senders) would silently
     *  block it. */
    private void registerLedcarReceiver() {
        if (ledcarReceiverRegistered) return;
        try {
            ledcarColorReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    int r = intent.getIntExtra("r", -1);
                    int g = intent.getIntExtra("g", -1);
                    int b = intent.getIntExtra("b", -1);
                    if (r < 0 || g < 0 || b < 0) return;
                    ledcarColor = Color.rgb(
                            clampInt(r, 0, 255), clampInt(g, 0, 255), clampInt(b, 0, 255));
                    prefs.setLedcarLastColor(ledcarColor);
                    refreshVisuals();
                }
            };
            IntentFilter f = new IntentFilter("com.ledcar01.controller.COLOR_CHANGED");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(ledcarColorReceiver, f, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(ledcarColorReceiver, f);
            }
            ledcarReceiverRegistered = true;
        } catch (Exception e) {
            android.util.Log.e("VolumeOverlayService", "registerLedcarReceiver failed", e);
        }
    }

    private void unregisterLedcarReceiver() {
        if (!ledcarReceiverRegistered) return;
        try { if (ledcarColorReceiver != null) unregisterReceiver(ledcarColorReceiver); } catch (Exception ignored) {}
        ledcarReceiverRegistered = false;
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

        // Drop shadow on the icon glyph itself, not a rectangular one behind
        // the whole ImageView (elevation/outline shadows would just be a box,
        // wrong for a non-square icon shape) - Paint.setShadowLayer() only
        // takes effect in a software-rendered layer, so the icon is forced
        // into one just for this. Set once here, not per refreshVisuals()
        // call - the paint itself never needs to change.
        ImageView icon = tabRoot.findViewById(R.id.tabIcon);
        if (icon != null) {
            Paint shadowPaint = new Paint();
            shadowPaint.setShadowLayer(dp(2), 0, dp(1.5f), Color.argb(110, 0, 0, 0));
            icon.setLayerType(View.LAYER_TYPE_SOFTWARE, shadowPaint);
        }
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

    /** Auto-closes the panel back to the bubble after panelHideSeconds (Panel
     *  tab's "Hide after" slider) of no interaction - scheduleAutoClosePanel()
     *  (re)arms it on open and on every real interaction (drag, nudge,
     *  theme-hold), and it stands down entirely while the settings popup is
     *  open (an active configuration session shouldn't get yanked away),
     *  resuming fresh once that closes. */
    private final Runnable autoClosePanelRunnable = () -> {
        if (panelAdded && !themePopupAdded) closePanel();
    };

    private void scheduleAutoClosePanel() {
        mainHandler.removeCallbacks(autoClosePanelRunnable);
        if (panelAdded && !themePopupAdded) mainHandler.postDelayed(autoClosePanelRunnable, panelHideSeconds * 1000L);
    }

    private void openPanel() {
        try {
            addPanelBackdrop(); // added first so panelRoot (added next) draws on top and keeps its own touches
            if (panelRoot == null) inflatePanel();
            panelParams = newOverlayParams(dp(panelWidthDp), WindowManager.LayoutParams.WRAP_CONTENT);
            positionPanel(); // computes the FINAL x/y/width into panelParams and measures panelRoot for its height
            int endW = panelParams.width;
            int endH = panelRoot.getMeasuredHeight();
            int endX = panelParams.x;
            int endY = panelParams.y;

            // Shape-morph open: the bubble's own current geometry is the
            // animation's starting point - capture it before removeTabWindow()
            // takes it away. openPanel() is only ever reached with the tab
            // actually showing (a genuine tap on it), so tabParams reflects
            // it; the fallback to the end geometry only matters if that
            // ever stops being true.
            int startW = tabAdded && tabParams != null ? tabParams.width : endW;
            int startH = tabAdded && tabParams != null ? tabParams.height : endH;
            int startX = tabAdded && tabParams != null ? tabParams.x : endX;
            int startY = tabAdded && tabParams != null ? tabParams.y : endY;

            removeTabWindow();

            panelParams.width = startW;
            panelParams.height = startH;
            panelParams.x = startX;
            panelParams.y = startY;
            wm.addView(panelRoot, panelParams);
            panelAdded = true;
            refreshVisuals(); // correct readout/color/shape from the first frame - just invisible until the content cross-fade catches up

            animateMorphOpen(startW, startH, startX, startY, endW, endH, endX, endY);
        } catch (Exception e) {
            android.util.Log.e("VolumeOverlayService", "openPanel failed", e);
            removePanelBackdrop();
            addTabWindow(); // fall back to just the tab rather than leaving nothing on screen
        }
    }

    /** "Grow from bubble" open transition: one window (panelRoot itself,
     *  already carrying the bubble's own geometry when this starts - see
     *  openPanel()) is animated from that geometry to the panel's own,
     *  its background's corner radii morphing from the bubble's fully
     *  rounded shape to the panel's own (panelCornerRadii()) in lockstep -
     *  the same element stretching, not a crossfade between two. The panel
     *  card's actual content (readout/eqBar/buttons) can't itself morph
     *  from a bubble - it cross-fades in over the back half of the run. */
    private void animateMorphOpen(int startW, int startH, int startX, int startY,
                                   int endW, int endH, int endX, int endY) {
        try {
            int color = colorForCurrent(getRawVolume());
            View panelCard = panelRoot.findViewById(R.id.panelCard);
            float bubbleRadius = dp(999);
            // Must match whatever Panel background shape (Form tab) is
            // actually selected, not just assume Themed - morphing toward
            // the wrong shape the whole time and then snapping to the
            // real one only once the animation ends is exactly the "shape
            // bug" this was fixing. Null means Clear: no background at
            // any point during the animation either, same as the settled
            // state already had.
            float[] endRadii = targetPanelCornerRadii();

            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(340);
            anim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
            anim.addUpdateListener(a -> {
                try {
                    float t = (float) a.getAnimatedValue();
                    panelParams.width = Math.round(startW + (endW - startW) * t);
                    panelParams.height = Math.round(startH + (endH - startH) * t);
                    panelParams.x = Math.round(startX + (endX - startX) * t);
                    panelParams.y = Math.round(startY + (endY - startY) * t);
                    if (panelAdded) wm.updateViewLayout(panelRoot, panelParams);

                    if (panelCard != null) {
                        if (endRadii != null) {
                            GradientDrawable bg = new GradientDrawable();
                            bg.setColor(mixColors(Color.parseColor("#E6E2D8"), color, 0.22f));
                            float[] radii = new float[8];
                            for (int i = 0; i < 8; i++) radii[i] = bubbleRadius + (endRadii[i] - bubbleRadius) * t;
                            bg.setCornerRadii(radii);
                            panelCard.setBackground(bg);
                        } else {
                            panelCard.setBackground(null);
                        }

                        if (panelCard instanceof ViewGroup) {
                            float contentAlpha = Math.max(0f, Math.min(1f, (t - 0.35f) / 0.65f));
                            ViewGroup vg = (ViewGroup) panelCard;
                            for (int i = 0; i < vg.getChildCount(); i++) vg.getChildAt(i).setAlpha(contentAlpha);
                        }
                    }
                } catch (Exception ignored) {}
            });
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    settleMorphOpen(panelCard, endW, endX, endY);
                }
            });
            anim.start();
        } catch (Exception e) {
            android.util.Log.e("VolumeOverlayService", "animateMorphOpen failed", e);
            settleMorphOpen(panelRoot.findViewById(R.id.panelCard), endW, endX, endY);
        }
    }

    /** Snaps the panel to its real final state - WRAP_CONTENT height
     *  (auto-resizes correctly again for any later Size tab change) and
     *  full content opacity, then refreshVisuals() restores the real
     *  themed background/shape cleanly instead of the animation's
     *  last interpolated frame. */
    private void settleMorphOpen(View panelCard, int endW, int endX, int endY) {
        try {
            if (panelCard instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) panelCard;
                for (int i = 0; i < vg.getChildCount(); i++) vg.getChildAt(i).setAlpha(1f);
            }
            panelParams.width = endW;
            panelParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
            panelParams.x = endX;
            panelParams.y = endY;
            if (panelAdded) wm.updateViewLayout(panelRoot, panelParams);
            refreshVisuals();
            scheduleAutoClosePanel();
        } catch (Exception ignored) {}
    }

    private void closePanel() {
        hideThemePopup(); // may itself reschedule the auto-close timer (settings just closed) - cancelled again right below
        mainHandler.removeCallbacks(autoClosePanelRunnable);
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
        eqBar.setFormIndex(formIndex);

        readoutRow.setOnTouchListener(this::onThemeHoldTouch);
        collapseBtn.setOnClickListener(v -> closePanel());
        nudgeBtn.setOnClickListener(v -> onNudgeClick());
        eqBar.setListener(new EqBarView.Listener() {
            @Override public void onDragValue(int value0toMax) { setRealVolume(value0toMax); scheduleAutoClosePanel(); }
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
        themeGridScroll = themePopupRoot.findViewById(R.id.themeGridScroll);
        themeCurrent = themePopupRoot.findViewById(R.id.themeCurrent);
        Button themeDone = themePopupRoot.findViewById(R.id.themeDone);
        ImageButton themeClose = themePopupRoot.findViewById(R.id.themeClose);
        ImageButton dragHandle = themePopupRoot.findViewById(R.id.dragHandle);
        dynamicCheck = themePopupRoot.findViewById(R.id.dynamicCheck);
        ledcarSyncCheck = themePopupRoot.findViewById(R.id.ledcarSyncCheck);
        customRgbPanel = themePopupRoot.findViewById(R.id.customRgbPanel);
        customRSeek = themePopupRoot.findViewById(R.id.customRSeek);
        customGSeek = themePopupRoot.findViewById(R.id.customGSeek);
        customBSeek = themePopupRoot.findViewById(R.id.customBSeek);
        customRgbHex = themePopupRoot.findViewById(R.id.customRgbHex);
        customRgbPreview = themePopupRoot.findViewById(R.id.customRgbPreview);
        customSwatchSlot = themePopupRoot.findViewById(R.id.customSwatchSlot);

        tabTheme = themePopupRoot.findViewById(R.id.tabTheme);
        tabConf = themePopupRoot.findViewById(R.id.tabConf);
        tabBubble = themePopupRoot.findViewById(R.id.tabBubble);
        tabPanel = themePopupRoot.findViewById(R.id.tabPanel);
        themeTabContent = themePopupRoot.findViewById(R.id.themeTabContent);
        confTabContent = themePopupRoot.findViewById(R.id.confTabContent);
        bubbleTabContent = themePopupRoot.findViewById(R.id.bubbleTabContent);
        panelTabContent = themePopupRoot.findViewById(R.id.panelTabContent);
        bubbleList = themePopupRoot.findViewById(R.id.bubbleList);
        panelList = themePopupRoot.findViewById(R.id.panelList);

        bubbleSizeLabel = themePopupRoot.findViewById(R.id.bubbleSizeLabel);
        panelWidthLabel = themePopupRoot.findViewById(R.id.panelWidthLabel);
        panelHeightLabel = themePopupRoot.findViewById(R.id.panelHeightLabel);
        panelHideLabel = themePopupRoot.findViewById(R.id.panelHideLabel);
        bubbleSizeSeek = themePopupRoot.findViewById(R.id.bubbleSizeSeek);
        panelWidthSeek = themePopupRoot.findViewById(R.id.panelWidthSeek);
        panelHeightSeek = themePopupRoot.findViewById(R.id.panelHeightSeek);
        panelHideSeek = themePopupRoot.findViewById(R.id.panelHideSeek);

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
        tabConf.setOnClickListener(v -> selectSettingsTab(1));
        tabBubble.setOnClickListener(v -> selectSettingsTab(2));
        tabPanel.setOnClickListener(v -> selectSettingsTab(3));

        dynamicCheck.setChecked(dynamicColor);
        dynamicCheck.setOnCheckedChangeListener((btn, checked) -> {
            dynamicColor = checked;
            prefs.setDynamicColor(checked);
            refreshVisuals();
        });

        ledcarSyncCheck.setChecked(ledcarSyncEnabled);
        ledcarSyncCheck.setOnCheckedChangeListener((btn, checked) -> {
            ledcarSyncEnabled = checked;
            prefs.setLedcarSync(checked);
            if (checked) registerLedcarReceiver(); else unregisterLedcarReceiver();
            refreshVisuals();
        });

        wireCustomRgbSeek(customRSeek);
        wireCustomRgbSeek(customGSeek);
        wireCustomRgbSeek(customBSeek);

        populateThemeGrid();
        setCustomRgbPanelVisible(themeIndex == CUSTOM_THEME_INDEX); // reopening the popup with Custom already active
        wireSizeTab();
        wireConfTab();
        populateBubbleList();
        populatePanelList();
    }

    /** Live-updates customColor (and the whole widget's visuals) on every
     *  tick while dragging, same "smooth while dragging, commit on release"
     *  split as onSeek()'s Size/Conf sliders - three synchronous Prefs
     *  writes per drag tick would be needless disk I/O for a value nothing
     *  else reads until the drag actually stops. */
    private void wireCustomRgbSeek(SeekBar sb) {
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                customColor = Color.rgb(customRSeek.getProgress(), customGSeek.getProgress(), customBSeek.getProgress());
                updateCustomRgbPreview();
                refreshVisuals();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { prefs.setCustomColor(customColor); }
        });
    }

    private void selectSettingsTab(int index) {
        themeTabContent.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        confTabContent.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        bubbleTabContent.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        panelTabContent.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        styleSettingsTab(tabTheme, index == 0);
        styleSettingsTab(tabConf, index == 1);
        styleSettingsTab(tabBubble, index == 2);
        styleSettingsTab(tabPanel, index == 3);
        showLiveBubblePreview(index == 2);
        // Each tab's content is a different height - re-layout the window
        // now that tabContent's measured height has changed.
        if (themePopupAdded) {
            wm.updateViewLayout(themePopupRoot, themePopupParams);
            // The window resizes here, but its Y position doesn't move on
            // its own - if the popup was sitting low on screen (default
            // centering, or a remembered drag position) and this tab is
            // taller than the one before it (Form's 16-row list vs Theme's
            // grid, say), the new bottom edge can run past the actual
            // screen height with nothing able to scroll into space that
            // isn't there. Re-clamp once the resize has actually taken
            // effect (post(), not immediately - updateViewLayout()'s
            // relayout happens on the next traversal, so getHeight() here
            // would still read the old, pre-switch height).
            themePopupRoot.post(() -> {
                try {
                    if (!themePopupAdded) return;
                    int actualH = themePopupRoot.getHeight();
                    if (actualH <= 0) return;
                    Point sz = screenSize();
                    int clampedY = clampInt(themePopupParams.y, 0, Math.max(0, sz.y - actualH));
                    if (clampedY != themePopupParams.y) {
                        themePopupParams.y = clampedY;
                        wm.updateViewLayout(themePopupRoot, themePopupParams);
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    private void styleSettingsTab(TextView tab, boolean selected) {
        tab.setBackgroundResource(selected ? R.drawable.bg_done_button : R.drawable.bg_small_button);
        tab.setTextColor(selected ? ContextCompat.getColor(this, R.color.cream) : Color.parseColor("#8A7A5C"));
    }

    /** Bubble tab's settings (background shape, icon) only ever show on the
     *  real floating bubble - which doesn't exist right now, since opening
     *  the panel already swapped it out for the panel window (see
     *  openPanel()). While on this tab, hide the (dimmed, non-interactive
     *  behind the settings backdrop) panel and add the real bubble window
     *  back in its place so changes here show live on the actual thing, not
     *  just the small size-preview swatch. hideThemePopup() always restores
     *  the panel and removes this again, whichever tab was last active. */
    private void showLiveBubblePreview(boolean show) {
        try {
            if (show) {
                if (panelRoot != null) panelRoot.setVisibility(View.INVISIBLE);
                addTabWindow();
                refreshVisuals();
            } else {
                removeTabWindow();
                if (panelRoot != null) panelRoot.setVisibility(View.VISIBLE);
            }
        } catch (Exception e) {
            android.util.Log.e("VolumeOverlayService", "showLiveBubblePreview failed", e);
        }
    }

    // ---------------------------------------------------------- Size tab

    private void wireSizeTab() {
        bubbleSizeSeek.setMax(BUBBLE_WIDTH_MAX_DP - BUBBLE_WIDTH_MIN_DP);
        panelWidthSeek.setMax(PANEL_WIDTH_MAX_DP - PANEL_WIDTH_MIN_DP);
        panelHeightSeek.setMax(Math.max(1, maxPanelBarHeightDp() - PANEL_BAR_HEIGHT_MIN_DP));
        panelHideSeek.setMax(PANEL_HIDE_MAX_S - PANEL_HIDE_MIN_S);
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
        onSeek(panelHideSeek, v -> {
            panelHideSeconds = PANEL_HIDE_MIN_S + v;
            panelHideLabel.setText("Hide after: " + panelHideSeconds + "s");
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
        panelHideSeek.setProgress(panelHideSeconds - PANEL_HIDE_MIN_S);
        panelHideLabel.setText("Hide after: " + panelHideSeconds + "s");
    }

    /** Live-resizes the floating bubble as the Bubble tab's size slider
     *  moves. This slider only lives on the Bubble tab, which always has
     *  the real bubble window showing while it's open (see
     *  showLiveBubblePreview()), so positionTab() applying the new size to
     *  the real tabParams every tick is itself the live preview - no
     *  separate fake swatch needed. */
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
            prefs.setPanelHideSeconds(panelHideSeconds);
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
            scheduleAutoClosePanel();
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
                scheduleAutoClosePanel();
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
            refreshVisuals(); // syncs the theme grid selection + bubble-size preview to the current state right away, not just after the next change
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
        showLiveBubblePreview(false); // undo the Bubble tab's live-bubble swap, whichever tab was last active
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
        scheduleAutoClosePanel(); // settings closed, focus is back on the plain panel - resume the countdown fresh
    }

    private void populateThemeGrid() {
        themeGrid.removeAllViews();

        // "Custom" gets its own row above the preset grid (customSwatchSlot,
        // not themeGrid) so its RGB panel always sits right under this
        // button, with the preset themes below both - not one of
        // ThemeColors.THEMES. Tapping it selects it (like any other swatch)
        // and toggles the RGB slider panel open/closed, so the sliders are
        // only ever showing while there's actually a Custom color to tune.
        customSwatchSlot.removeAllViews();
        customSwatch = LayoutInflater.from(themedCtx).inflate(R.layout.theme_swatch_item, customSwatchSlot, false);
        View customBall = customSwatch.findViewById(R.id.ball);
        TextView customName = customSwatch.findViewById(R.id.tname);
        customName.setText("Custom");
        GradientDrawable customBg = new GradientDrawable();
        customBg.setColor(customColor);
        customBg.setShape(GradientDrawable.OVAL);
        customBall.setBackground(customBg);
        customSwatch.setOnClickListener(v -> {
            try {
                boolean alreadySelected = themeIndex == CUSTOM_THEME_INDEX;
                themeIndex = CUSTOM_THEME_INDEX;
                prefs.setTheme(CUSTOM_THEME_INDEX);
                refreshVisuals();
                setCustomRgbPanelVisible(!alreadySelected || customRgbPanel.getVisibility() != View.VISIBLE);
            } catch (Exception e) {
                android.util.Log.e("VolumeOverlayService", "custom swatch click failed", e);
            }
        });
        customSwatchSlot.addView(customSwatch);

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
                    setCustomRgbPanelVisible(false);
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
        if (customSwatch != null) {
            View customBall = customSwatch.findViewById(R.id.ball);
            Drawable d = customBall.getBackground();
            if (d instanceof GradientDrawable) {
                GradientDrawable gd = (GradientDrawable) d;
                gd.setColor(customColor); // live - reflects slider drags immediately
                gd.setStroke(themeIndex == CUSTOM_THEME_INDEX ? dp(3) : 0, Color.parseColor("#3A2F1C"));
            }
        }
        if (themeCurrent != null) {
            themeCurrent.setText(themeIndex == CUSTOM_THEME_INDEX ? "Custom" : ThemeColors.THEMES[themeIndex].name);
        }
    }

    /** Shows/hides the Custom swatch's RGB slider panel - swapped with the
     *  preset theme grid, never shown alongside it, so picking Custom
     *  doesn't grow the popup taller: press Custom to collapse the grid
     *  down to just the RGB bars, press it again to collapse the bars back
     *  to the grid and pick a preset instead. When opening, syncs the three
     *  sliders to whatever customColor currently is - covers reopening the
     *  settings popup with Custom already selected from a previous session,
     *  not just the initial tap. */
    private void setCustomRgbPanelVisible(boolean visible) {
        if (customRgbPanel == null) return;
        customRgbPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (themeGridScroll != null) themeGridScroll.setVisibility(visible ? View.GONE : View.VISIBLE);
        if (visible) {
            customRSeek.setProgress(Color.red(customColor));
            customGSeek.setProgress(Color.green(customColor));
            customBSeek.setProgress(Color.blue(customColor));
            updateCustomRgbPreview();
        }
        // The popup window is WRAP_CONTENT and this can change its measured
        // height by a lot (RGB bars vs. the 220dp grid) - re-layout and
        // re-clamp Y the same way selectSettingsTab() does for a tab switch,
        // so a popup sitting low on screen doesn't run off the bottom edge.
        if (themePopupAdded) {
            wm.updateViewLayout(themePopupRoot, themePopupParams);
            themePopupRoot.post(() -> {
                try {
                    if (!themePopupAdded) return;
                    int actualH = themePopupRoot.getHeight();
                    if (actualH <= 0) return;
                    Point sz = screenSize();
                    int clampedY = clampInt(themePopupParams.y, 0, Math.max(0, sz.y - actualH));
                    if (clampedY != themePopupParams.y) {
                        themePopupParams.y = clampedY;
                        wm.updateViewLayout(themePopupRoot, themePopupParams);
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    private void updateCustomRgbPreview() {
        if (customRgbPreview == null) return;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(customColor);
        customRgbPreview.setBackground(d);
        if (customRgbHex != null) {
            customRgbHex.setText(String.format("#%06X", customColor & 0xFFFFFF));
        }
    }

    // ---------------------------------------------------------- Bubble tab / Panel tab pickers

    /** Bubble background shape, then the 16-icon picker grouped into its
     *  four categories - everything that only ever affects the floating
     *  bubble, all on the Bubble tab (see class doc on tabTheme's field). */
    private void populateBubbleList() {
        bubbleList.removeAllViews();

        addPickerSectionHeader(bubbleList, "Bubble background");
        for (int i = 0; i < BG_SHAPE_NAMES.length; i++) {
            final int idx = i;
            bubbleBgRows[i] = addPickerRow(bubbleList, BG_SHAPE_NAMES[i], () -> {
                bubbleBgShape = idx;
                prefs.setBubbleBgShape(idx);
                refreshPickerSelections();
                refreshVisuals();
            });
        }

        int categoryPos = 0;
        for (int i = 0; i < ICON_NAMES.length; i++) {
            if (categoryPos < ICON_CATEGORY_STARTS.length && i == ICON_CATEGORY_STARTS[categoryPos]) {
                addPickerSectionHeader(bubbleList, ICON_CATEGORY_NAMES[categoryPos]);
                categoryPos++;
            }
            final int idx = i;
            iconRows[i] = addPickerRow(bubbleList, ICON_NAMES[i], () -> {
                bubbleIconIndex = idx;
                prefs.setBubbleIcon(idx);
                refreshPickerSelections();
                refreshVisuals();
            });
        }

        refreshPickerSelections();
    }

    /** Panel style (the EQ bar's visual form), then Panel background shape -
     *  everything that only ever affects the docked side panel, all on the
     *  Panel tab. */
    private void populatePanelList() {
        panelList.removeAllViews();

        String[] names = EqBarView.FORM_NAMES;
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            formRows[i] = addPickerRow(panelList, names[i], () -> {
                formIndex = idx;
                prefs.setForm(idx);
                if (eqBar != null) eqBar.setFormIndex(idx);
                refreshPickerSelections();
            });
        }

        addPickerSectionHeader(panelList, "Panel background");
        for (int i = 0; i < BG_SHAPE_NAMES.length; i++) {
            final int idx = i;
            panelBgRows[i] = addPickerRow(panelList, BG_SHAPE_NAMES[i], () -> {
                panelBgShape = idx;
                prefs.setPanelBgShape(idx);
                refreshPickerSelections();
                refreshVisuals();
            });
        }

        refreshPickerSelections();
    }

    private void addPickerSectionHeader(LinearLayout container, String text) {
        TextView header = new TextView(themedCtx);
        header.setText(text);
        header.setAllCaps(true);
        header.setTextSize(10);
        header.setTextColor(Color.parseColor("#8A7A5C"));
        header.setPadding(dp(10), dp(14), dp(10), dp(4));
        container.addView(header);
    }

    private TextView addPickerRow(LinearLayout container, String name, Runnable onSelect) {
        TextView row = new TextView(themedCtx);
        row.setText(name);
        row.setTextSize(13);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(2);
        row.setLayoutParams(lp);
        row.setOnClickListener(v -> {
            try {
                onSelect.run();
            } catch (Exception e) {
                android.util.Log.e("VolumeOverlayService", "picker row click failed", e);
            }
        });
        container.addView(row);
        return row;
    }

    private void refreshPickerSelections() {
        styleFormRows(iconRows, bubbleIconIndex);
        styleFormRows(formRows, formIndex);
        styleFormRows(panelBgRows, panelBgShape);
        styleFormRows(bubbleBgRows, bubbleBgShape);
    }

    private void styleFormRows(View[] rows, int selectedIndex) {
        for (int i = 0; i < rows.length; i++) {
            if (!(rows[i] instanceof TextView)) continue;
            TextView row = (TextView) rows[i];
            boolean selected = i == selectedIndex;
            row.setBackgroundResource(selected ? R.drawable.bg_done_button : R.drawable.bg_small_button);
            row.setTextColor(selected ? ContextCompat.getColor(this, R.color.cream) : Color.parseColor("#3A2F1C"));
            row.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
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
            if (themePopupAdded) {
                refreshThemeGridSelection();
            }
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
        // "LEDCAR Set" overrides the theme entirely, once a color has
        // actually arrived - highest priority, same idea as Dynamic but
        // sourced from LEDCAR's own current color instead of this app's
        // own theme picker.
        if (ledcarSyncEnabled && ledcarColor != -1) return ledcarColor;
        int themeColor;
        if (themeIndex == CUSTOM_THEME_INDEX) {
            themeColor = customColor;
        } else {
            int idx = Math.max(0, Math.min(ThemeColors.THEMES.length - 1, themeIndex));
            themeColor = ThemeColors.THEMES[idx].mid;
        }
        if (!dynamicColor) return themeColor;
        float frac = widgetMax <= 0 ? 0f : Math.max(0f, Math.min(1f, raw / (float) widgetMax));
        return mixColors(themeColor, Color.parseColor("#DC2626"), frac);
    }

    private void updateTabAppearance(int volumeColor) {
        GradientDrawable bg = buildBubbleDrawable(bubbleBgShape, volumeColor);
        tabRoot.setBackground(bg);

        ImageView icon = tabRoot.findViewById(R.id.tabIcon);
        icon.setImageResource(ICON_DRAWABLES[clampInt(bubbleIconIndex, 0, ICON_DRAWABLES.length - 1)]);
        // Clear has no background to carry the volume color - tint the icon
        // itself instead so that signal isn't lost entirely.
        icon.setColorFilter(bubbleBgShape == 4 ? volumeColor : Color.parseColor("#77592A"));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) icon.getLayoutParams();
        int iconSize = dp(Math.round(bubbleWidthDp * TAB_ICON_RATIO));
        lp.width = iconSize;
        lp.height = iconSize;
        lp.gravity = Gravity.CENTER_VERTICAL | ("left".equals(side) ? Gravity.START : Gravity.END);
        lp.leftMargin = "left".equals(side) ? dp(8) : 0;
        lp.rightMargin = "right".equals(side) ? dp(8) : 0;
        icon.setLayoutParams(lp);
    }

    /** Builds the real floating bubble's own background. Null means "Clear" -
     *  no drawable, caller must handle that (setBackground(null) is fine). */
    private GradientDrawable buildBubbleDrawable(int shape, int volumeColor) {
        if (shape == 4) return null; // Clear
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(mixColors(Color.parseColor("#E6E2D8"), volumeColor, 0.4f));
        float r999 = dp(999);
        switch (shape) {
            case 1: bg.setCornerRadius(dp(14)); break; // Rounded
            case 2: bg.setCornerRadius(0); break; // Square
            case 3: bg.setCornerRadius(r999); break; // Pill
            default: // Themed - flat against the docked edge, rounded facing the screen
                if ("left".equals(side)) bg.setCornerRadii(new float[]{0, 0, r999, r999, r999, r999, 0, 0});
                else bg.setCornerRadii(new float[]{r999, r999, 0, 0, 0, 0, r999, r999});
        }
        return bg;
    }

    /** Re-skins the whole open panel (card, buttons, hold-progress fill) in
     *  the current theme's volume color, not just the EQ ball - card corner
     *  shape (Form tab's "Panel background") also flips to match whichever
     *  edge the panel is docked to when set to Themed, same "flat against
     *  the edge, rounded into the screen" logic as the bubble's own shape. */
    private void applyPanelTheme(int color) {
        if (panelRoot == null) return;
        View panelCard = panelRoot.findViewById(R.id.panelCard);
        if (panelCard != null) {
            if (panelBgShape == 4) {
                panelCard.setBackground(null); // Clear
            } else {
                GradientDrawable cardBg = new GradientDrawable();
                cardBg.setColor(mixColors(Color.parseColor("#E6E2D8"), color, 0.22f));
                switch (panelBgShape) {
                    case 1: cardBg.setCornerRadius(dp(18)); break; // Rounded
                    case 2: cardBg.setCornerRadius(0); break; // Square
                    case 3: cardBg.setCornerRadius(dp(999)); break; // Pill
                    default: cardBg.setCornerRadii(panelCornerRadii()); // Themed
                }
                panelCard.setBackground(cardBg);
            }
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

    /** The panel card's actual target corner radii for whichever Panel
     *  background shape (Form tab) is currently selected - same values
     *  applyPanelTheme() itself uses, kept in sync so the open-morph
     *  animation (animateMorphOpen()) targets the real shape instead of
     *  always assuming Themed. Null means Clear - no background shape at all. */
    private float[] targetPanelCornerRadii() {
        switch (panelBgShape) {
            case 1: { float r = dp(18); return new float[]{r, r, r, r, r, r, r, r}; }  // Rounded
            case 2: return new float[]{0, 0, 0, 0, 0, 0, 0, 0};                        // Square
            case 3: { float r = dp(999); return new float[]{r, r, r, r, r, r, r, r}; } // Pill
            case 4: return null;                                                       // Clear
            default: return panelCornerRadii();                                        // Themed
        }
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
