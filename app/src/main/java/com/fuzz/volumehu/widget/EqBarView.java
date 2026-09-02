package com.fuzz.volumehu.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * File:        EqBarView.java
 * Description: The chosen "EQ Segments" bar+ball - a stack of rounded meter
 *              blocks (fixed green-to-red gradient, same as the mockup) with
 *              a theme-colored ball marking the current position. Owns its
 *              own touch handling: a drag never reports a value above
 *              dragCap ("when go slowly" on the Conf tab, 20 by default) -
 *              going past that only happens through the panel's nudge arrow.
 *              Both dragCap and the bar's full-scale volMax ("max volume
 *              supported", 40 by default) are configurable at runtime via
 *              setDragCap()/setVolMax() - see VolumeOverlayService.
 * Author:      FuzzBC
 * Date:        2026-09-01
 */
public class EqBarView extends View {

    /** Fallback defaults - the Conf tab (VolumeOverlayService) overrides both
     *  via setVolMax()/setDragCap() from Prefs as soon as the panel inflates. */
    public static final int VOL_MAX = 25;
    public static final int DRAG_CAP = 20;

    /** Reports live drag values (dragEnd only fires once, on ACTION_UP/CANCEL). */
    public interface Listener {
        void onDragValue(int value0toMax);
        void onDragEnd();
    }

    private static final float[] STOP_POS = {0f, 0.30f, 0.58f, 0.80f, 1f};
    private static final String[] STOP_COLOR = {"#16A34A", "#84CC16", "#EAB308", "#F97316", "#DC2626"};

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint segPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ballFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ballBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();

    // Instance-level now (Conf tab's "max volume supported" / "when go slowly"
    // sliders replace these at runtime) - the class constants above only seed
    // the initial values so the view still works before Service configures it.
    private int volMax = VOL_MAX;
    private int dragCap = DRAG_CAP;
    private int barValue0toMax = 12; // clamped display value used for drawing (see Service: raw volume clamped to 0..volMax)
    private int ballColor = Color.parseColor("#D97706");
    private Listener listener;
    private boolean dragging = false;

    public EqBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        trackPaint.setColor(Color.parseColor("#DED9CC"));
        capPaint.setColor(Color.parseColor("#663A2F1C"));
        capPaint.setStrokeWidth(dp(2));
        ballFillPaint.setColor(Color.parseColor("#F4F1E8"));
        ballBorderPaint.setStyle(Paint.Style.STROKE);
        ballBorderPaint.setStrokeWidth(dp(3));
    }

    /** Sets the value this view draws - already clamped to 0..volMax by the caller. */
    public void setBarValue(int value0toMax) {
        barValue0toMax = Math.max(0, Math.min(volMax, value0toMax));
        invalidate();
    }

    /** Sets the ball's border color (the active theme's color at the current volume). */
    public void setBallColor(int color) {
        ballColor = color;
        invalidate();
    }

    /** The bar's full-scale top value - Conf tab's "max volume supported". */
    public void setVolMax(int max) {
        volMax = Math.max(1, max);
        barValue0toMax = Math.min(barValue0toMax, volMax);
        invalidate();
    }

    /** Where a direct drag stops (and the cap-line marker) - Conf tab's "when go slowly". */
    public void setDragCap(int cap) {
        dragCap = Math.max(0, Math.min(cap, volMax));
        invalidate();
    }

    public void setListener(Listener l) { listener = l; }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        try {
            drawInner(canvas);
        } catch (Exception e) {
            android.util.Log.e("EqBarView", "onDraw failed", e);
        }
    }

    private void drawInner(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return; // not laid out yet - LinearGradient needs a non-degenerate line
        float radius = w / 2f;
        trackRect.set(0, 0, w, h);
        canvas.drawRoundRect(trackRect, radius * 0.4f, radius * 0.4f, trackPaint);

        float fillFrac = barValue0toMax / (float) volMax;
        float fillTop = h * (1f - fillFrac);

        // Segmented fill: solid gradient rect, then cut cream gaps every few px.
        canvas.save();
        canvas.clipRect(0, fillTop, w, h);
        RectF fillRect = new RectF(0, 0, w, h);
        segPaint.setShader(new android.graphics.LinearGradient(
                0, h, 0, 0, stopColors(), STOP_POS, android.graphics.Shader.TileMode.CLAMP));
        canvas.drawRoundRect(fillRect, radius * 0.4f, radius * 0.4f, segPaint);
        float gap = dp(3), seg = dp(5);
        for (float y = h; y > fillTop - seg; y -= (seg + gap)) {
            canvas.drawRect(0, y - gap, w, y, trackPaint);
        }
        canvas.restore();

        // Cap line marking the drag ceiling ("when go slowly", up from the bottom).
        float capY = h * (1f - dragCap / (float) volMax);
        canvas.drawLine(-dp(2), capY, w + dp(2), capY, capPaint);

        // Ball marker at the current position.
        float ballR = dp(11);
        float ballY = Math.max(ballR, Math.min(h - ballR, fillTop));
        ballBorderPaint.setColor(ballColor);
        canvas.drawCircle(w / 2f, ballY, ballR, ballFillPaint);
        canvas.drawCircle(w / 2f, ballY, ballR, ballBorderPaint);
    }

    private int[] stopColors() {
        int[] c = new int[STOP_COLOR.length];
        for (int i = 0; i < c.length; i++) c[i] = Color.parseColor(STOP_COLOR[i]);
        return c;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        try {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    reportDrag(event.getY());
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) reportDrag(event.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging && listener != null) listener.onDragEnd();
                    dragging = false;
                    return true;
            }
        } catch (Exception e) {
            android.util.Log.e("EqBarView", "onTouchEvent failed", e);
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void reportDrag(float y) {
        float h = getHeight();
        float t = 1f - Math.max(0f, Math.min(1f, y / h));
        int raw = Math.round(t * volMax);
        int capped = Math.min(raw, dragCap);
        if (listener != null) listener.onDragValue(Math.max(0, capped));
    }
}
