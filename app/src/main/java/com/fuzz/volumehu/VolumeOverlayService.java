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
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.fuzz.volumehu.widget.EqBarView;
import com.fuzz.volumehu.widget.ThemeColors;

/**
 * File:        VolumeOverlayService.java
 * Description: The whole floating widget - the always-on half-circle tab
 *              and the expandable panel (EQ-segments bar/ball, nudge arrow,
 *              5s-hold theme popup, collapse arrow). Runs as a foreground
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
    private static final long THEME_HOLD_MS = 5000;
    private static final long NUDGE_COOLDOWN_MS = 500;
    private static final int WIDGET_MAX = 25; // this widget's own ceiling - see class doc

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

    private View tabRoot;
    private WindowManager.LayoutParams tabParams;
    private boolean tabAdded = false;

    private View panelRoot;
    private WindowManager.LayoutParams panelParams;
    private boolean panelAdded = false;

    private TextView volNum;
    private View holdProgressFill;
    private ImageButton nudgeBtn;
    private ImageButton collapseBtn;
    private EqBarView eqBar;
    private View readoutRow;
    private View themePopup;
    private GridLayout themeGrid;
    private TextView themeCurrent;
    private final View[] themeSwatches = new View[ThemeColors.THEMES.length];

    private android.animation.ObjectAnimator holdAnim;
    private boolean nudgeLocked = false;

    private BroadcastReceiver volumeReceiver;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        prefs = new Prefs(this);
        themedCtx = new ContextThemeWrapper(this, R.style.AppTheme);
        mainHandler = new Handler(Looper.getMainLooper());
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        side = prefs.getSide();
        vpos = prefs.getVpos();
        themeIndex = prefs.getTheme();

        try {
            createNotificationChannel();
            startForeground(NOTIF_ID, buildNotification());
            prefs.setOverlayStarted(true);

            registerVolumeReceiver();
            buildTabView();
            addTabWindow();
            refreshVisuals();
        } catch (Exception e) {
            // Never let a WindowManager/notification quirk on unusual firmware
            // crash the whole process - stop cleanly instead, MainActivity's
            // status text just shows "stopped" and the user can retry.
            android.util.Log.e("VolumeOverlayService", "startup failed, stopping", e);
            prefs.setOverlayStarted(false);
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        prefs.setOverlayStarted(false);
        if (volumeReceiver != null) { try { unregisterReceiver(volumeReceiver); } catch (Exception ignored) {} }
        if (mainHandler != null) mainHandler.removeCallbacksAndMessages(null);
        try { removeTabWindow(); } catch (Exception ignored) {}
        try { if (panelAdded) { wm.removeView(panelRoot); panelAdded = false; } } catch (Exception ignored) {}
        stopForeground(true);
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

    /** Writes a new volume, always capped at this widget's own ceiling (WIDGET_MAX). */
    private void setRealVolume(int desired) {
        try {
            int clamped = Math.max(0, Math.min(Math.min(desired, WIDGET_MAX), getStreamMax()));
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

    private void addTabWindow() {
        if (tabAdded) return;
        try {
            tabParams = newOverlayParams(dp(52), dp(108));
            positionTab();
            wm.addView(tabRoot, tabParams);
            tabAdded = true;
        } catch (Exception e) {
            android.util.Log.e("VolumeOverlayService", "addTabWindow failed, stopping", e);
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
        int tabW = dp(52), tabH = dp(108);
        int x = "left".equals(side) ? 0 : sz.x - tabW;
        int y = Math.round((vpos / 100f) * sz.y - tabH / 2f);
        y = clampInt(y, 0, sz.y - tabH);
        tabParams.x = x;
        tabParams.y = y;
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
                    side = event.getRawX() > sz.x / 2f ? "right" : "left";
                    positionTab();
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
            if (panelRoot == null) inflatePanel();
            panelParams = newOverlayParams(dp(184), WindowManager.LayoutParams.WRAP_CONTENT);
            positionPanel();
            wm.addView(panelRoot, panelParams);
            panelAdded = true;
            refreshVisuals();
        } catch (Exception e) {
            android.util.Log.e("VolumeOverlayService", "openPanel failed", e);
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
        addTabWindow();
    }

    private void positionPanel() {
        Point sz = screenSize();
        int panelW = dp(184);
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
        if (panelAdded) wm.updateViewLayout(panelRoot, panelParams);
    }

    private void inflatePanel() {
        panelRoot = LayoutInflater.from(themedCtx).inflate(R.layout.overlay_panel, null);
        readoutRow = panelRoot.findViewById(R.id.readoutRow);
        volNum = panelRoot.findViewById(R.id.volNum);
        holdProgressFill = panelRoot.findViewById(R.id.holdProgressFill);
        holdProgressFill.setPivotX(0f);
        nudgeBtn = panelRoot.findViewById(R.id.nudgeBtn);
        collapseBtn = panelRoot.findViewById(R.id.collapseBtn);
        eqBar = panelRoot.findViewById(R.id.eqBar);
        themePopup = panelRoot.findViewById(R.id.themePopup);
        themeGrid = panelRoot.findViewById(R.id.themeGrid);
        themeCurrent = panelRoot.findViewById(R.id.themeCurrent);
        Button themeDone = panelRoot.findViewById(R.id.themeDone);
        ImageButton themeClose = panelRoot.findViewById(R.id.themeClose);

        readoutRow.setOnTouchListener(this::onThemeHoldTouch);
        collapseBtn.setOnClickListener(v -> closePanel());
        nudgeBtn.setOnClickListener(v -> onNudgeClick());
        themeDone.setOnClickListener(v -> hideThemePopup());
        themeClose.setOnClickListener(v -> hideThemePopup());
        eqBar.setListener(new EqBarView.Listener() {
            @Override public void onDragValue(int value0to25) { setRealVolume(value0to25); }
            @Override public void onDragEnd() { /* already applied live */ }
        });

        populateThemeGrid();
    }

    private void onNudgeClick() {
        try {
            if (nudgeLocked) return;
            int raw = getRawVolume();
            if (raw >= WIDGET_MAX) return;
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

    // ---------------------------------------------------------- 5s hold -> theme popup

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
            themePopup.setVisibility(View.VISIBLE);
            refreshThemeGridSelection();
            positionPanel();
        } catch (Exception e) {
            android.util.Log.e("VolumeOverlayService", "showThemePopup failed", e);
        }
    }

    private void hideThemePopup() {
        try {
            themePopup.setVisibility(View.GONE);
            positionPanel();
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
            int barVal = Math.max(0, Math.min(WIDGET_MAX, raw));
            int color = ThemeColors.colorFor(themeIndex, barVal);

            updateTabAppearance(color);

            if (panelAdded && volNum != null) {
                volNum.setText(String.valueOf(raw));
                eqBar.setBarValue(barVal);
                eqBar.setBallColor(color);
                boolean showNudge = raw >= EqBarView.DRAG_CAP && raw < WIDGET_MAX;
                nudgeBtn.setVisibility(showNudge ? View.VISIBLE : View.INVISIBLE);
                collapseBtn.setRotation("right".equals(side) ? 90f : -90f);
                if (themePopup.getVisibility() == View.VISIBLE) refreshThemeGridSelection();
            }
        } catch (Exception e) {
            android.util.Log.e("VolumeOverlayService", "refreshVisuals failed", e);
        }
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
        lp.gravity = Gravity.CENTER_VERTICAL | ("left".equals(side) ? Gravity.START : Gravity.END);
        lp.leftMargin = "left".equals(side) ? dp(8) : 0;
        lp.rightMargin = "right".equals(side) ? dp(8) : 0;
        icon.setLayoutParams(lp);
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
