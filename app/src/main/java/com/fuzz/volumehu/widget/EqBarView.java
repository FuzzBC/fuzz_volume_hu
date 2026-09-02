package com.fuzz.volumehu.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * File:        EqBarView.java
 * Description: The volume meter itself - 16 selectable visual "forms" (Form
 *              tab: 0 is the original "EQ Segments" look, 1-15 are the
 *              additional forms added afterward), all driven by the exact
 *              same touch handling: a vertical drag, never reporting a
 *              value above dragCap ("when go slowly" on the Conf tab, 20 by
 *              default) - going past that only happens through the panel's
 *              nudge arrow. Deliberately one interaction model for every
 *              form, however different they look, so switching forms never
 *              changes how the widget responds to a drag. Both dragCap and
 *              the bar's full-scale volMax ("max volume supported", 40 by
 *              default) are configurable at runtime via setDragCap()/
 *              setVolMax() - see VolumeOverlayService.
 * Author:      FuzzBC
 * Date:        2026-09-01
 */
public class EqBarView extends View {

    /** Fallback defaults - the Conf tab (VolumeOverlayService) overrides both
     *  via setVolMax()/setDragCap() from Prefs as soon as the panel inflates. */
    public static final int VOL_MAX = 25;
    public static final int DRAG_CAP = 20;

    /** Names shown on the Form tab, in index order - index into these IS the
     *  form value stored in Prefs (KEY_FORM) and passed to setFormIndex(). */
    public static final String[] FORM_NAMES = {
            "Classic EQ", "Clear mode", "Liquid fill", "Dial", "Speedometer",
            "Minimal line", "Chunky LED", "Neumorphic", "Glass", "Dot column",
            "Ring", "Thermometer", "Equalizer wave", "Icon header", "Compact pill", "Retro LCD",
    };

    /** Reports live drag values (dragEnd only fires once, on ACTION_UP/CANCEL). */
    public interface Listener {
        void onDragValue(int value0toMax);
        void onDragEnd();
    }

    private static final float[] STOP_POS = {0f, 0.30f, 0.58f, 0.80f, 1f};
    private static final String[] STOP_COLOR = {"#16A34A", "#84CC16", "#EAB308", "#F97316", "#DC2626"};

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ballFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ballBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    // Instance-level now (Conf tab's "max volume supported" / "when go slowly"
    // sliders replace these at runtime) - the class constants above only seed
    // the initial values so the view still works before Service configures it.
    private int volMax = VOL_MAX;
    private int dragCap = DRAG_CAP;
    private int barValue0toMax = 12; // clamped display value used for drawing (see Service: raw volume clamped to 0..volMax)
    private int ballColor = Color.parseColor("#D97706");
    private int formIndex = 0;
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
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
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

    /** Which of FORM_NAMES to render - Form tab. */
    public void setFormIndex(int index) {
        formIndex = Math.max(0, Math.min(FORM_NAMES.length - 1, index));
        invalidate();
    }

    public void setListener(Listener l) { listener = l; }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        try {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return; // not laid out yet - LinearGradient needs a non-degenerate line
            float fillFrac = Math.max(0f, Math.min(1f, barValue0toMax / (float) volMax));
            float fillTop = h * (1f - fillFrac);
            switch (formIndex) {
                case 1: drawClear(canvas, w, h, fillFrac, fillTop); break;
                case 2: drawLiquid(canvas, w, h, fillFrac, fillTop); break;
                case 3: drawDial(canvas, w, h, fillFrac); break;
                case 4: drawSpeedometer(canvas, w, h, fillFrac); break;
                case 5: drawMinimalLine(canvas, w, h, fillFrac, fillTop); break;
                case 6: drawChunky(canvas, w, h, fillFrac); break;
                case 7: drawNeumorphic(canvas, w, h, fillFrac, fillTop); break;
                case 8: drawGlass(canvas, w, h, fillFrac, fillTop); break;
                case 9: drawDots(canvas, w, h, fillFrac); break;
                case 10: drawRing(canvas, w, h, fillFrac); break;
                case 11: drawThermometer(canvas, w, h, fillFrac, fillTop); break;
                case 12: drawWave(canvas, w, h, fillFrac); break;
                case 13: drawIconHeader(canvas, w, h, fillFrac); break;
                case 14: drawCompactPill(canvas, w, h, fillTop); break;
                case 15: drawRetroLcd(canvas, w, h, fillFrac); break;
                default: drawClassic(canvas, w, h, fillTop); break;
            }
        } catch (Exception e) {
            android.util.Log.e("EqBarView", "onDraw failed", e);
        }
    }

    // ---------------------------------------------------------- shared helpers

    /** Narrow centered column most forms draw in, even though the view itself
     *  is now full panel width (so round forms have room) - returns {left, width}. */
    private float[] barColumn(float w) {
        float barW = Math.min(w, dp(40));
        return new float[]{(w - barW) / 2f, barW};
    }

    private int[] stopColors() {
        int[] c = new int[STOP_COLOR.length];
        for (int i = 0; i < c.length; i++) c[i] = Color.parseColor(STOP_COLOR[i]);
        return c;
    }

    /** Green under 40%, amber to 75%, red above - used by the discrete-segment forms. */
    private int zoneColor(float frac) {
        if (frac < 0.40f) return Color.parseColor("#16A34A");
        if (frac < 0.75f) return Color.parseColor("#F59E0B");
        return Color.parseColor("#DC2626");
    }

    private void drawBall(Canvas c, float cx, float ballY) {
        float ballR = dp(11);
        ballBorderPaint.setColor(ballColor);
        c.drawCircle(cx, ballY, ballR, ballFillPaint);
        c.drawCircle(cx, ballY, ballR, ballBorderPaint);
    }

    // ---------------------------------------------------------- 0: Classic EQ (the original look)

    private void drawClassic(Canvas c, float w, float h, float fillTop) {
        float[] col = barColumn(w);
        float left = col[0], barW = col[1], radius = barW / 2f;
        rect.set(left, 0, left + barW, h);
        c.drawRoundRect(rect, radius * 0.4f, radius * 0.4f, trackPaint);

        c.save();
        c.clipRect(left, fillTop, left + barW, h);
        fillPaint.setShader(new android.graphics.LinearGradient(
                0, h, 0, 0, stopColors(), STOP_POS, Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, radius * 0.4f, radius * 0.4f, fillPaint);
        float gap = dp(3), seg = dp(5);
        for (float y = h; y > fillTop - seg; y -= (seg + gap)) {
            c.drawRect(left, y - gap, left + barW, y, trackPaint);
        }
        c.restore();

        float capY = h * (1f - dragCap / (float) volMax);
        c.drawLine(left - dp(2), capY, left + barW + dp(2), capY, capPaint);

        float ballR = dp(11);
        float ballY = Math.max(ballR, Math.min(h - ballR, fillTop));
        drawBall(c, left + barW / 2f, ballY);
    }

    // ---------------------------------------------------------- 1: Clear mode

    private void drawClear(Canvas c, float w, float h, float fillFrac, float fillTop) {
        float cx = w / 2f;
        linePaint.setStrokeWidth(dp(4));
        linePaint.setColor(ballColor);
        c.drawLine(cx, h, cx, fillTop, linePaint);
        float ballR = dp(11);
        drawBall(c, cx, Math.max(ballR, Math.min(h - ballR, fillTop)));
    }

    // ---------------------------------------------------------- 2: Liquid fill

    private void drawLiquid(Canvas c, float w, float h, float fillFrac, float fillTop) {
        float[] col = barColumn(w);
        float left = col[0], barW = col[1], radius = barW / 2f;
        rect.set(left, 0, left + barW, h);
        c.drawRoundRect(rect, radius, radius, trackPaint);

        c.save();
        c.clipRect(left, fillTop, left + barW, h);
        fillPaint.setShader(new android.graphics.LinearGradient(
                0, h, 0, fillTop, Color.parseColor("#DC2626"), ballColor, Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, radius, radius, fillPaint);
        c.restore();

        // Meniscus: a lighter cap right at the fill surface.
        fillPaint.setShader(null);
        fillPaint.setColor(mixColor(ballColor, Color.WHITE, 0.35f));
        c.save();
        c.clipRect(left, fillTop, left + barW, Math.min(h, fillTop + dp(6)));
        c.drawRoundRect(rect, radius, radius, fillPaint);
        c.restore();
    }

    // ---------------------------------------------------------- 3: Dial

    private void drawDial(Canvas c, float w, float h, float fillFrac) {
        float d = Math.min(w, h) * 0.82f;
        float cx = w / 2f, cy = h / 2f;
        float sw = Math.max(dp(6), d * 0.11f);
        rect.set(cx - d / 2f, cy - d / 2f, cx + d / 2f, cy + d / 2f);
        arcPaint.setStrokeWidth(sw);
        arcPaint.setColor(Color.parseColor("#DED9CC"));
        c.drawArc(rect, 135, 270, false, arcPaint);
        arcPaint.setColor(ballColor);
        c.drawArc(rect, 135, 270 * fillFrac, false, arcPaint);

        double angle = Math.toRadians(135 + 270 * fillFrac);
        float r1 = d / 2f - sw * 1.6f, r2 = d / 2f - sw * 0.2f;
        linePaint.setStrokeWidth(dp(3));
        linePaint.setColor(ballColor);
        c.drawLine(cx + (float) (Math.cos(angle) * r1), cy + (float) (Math.sin(angle) * r1),
                cx + (float) (Math.cos(angle) * r2), cy + (float) (Math.sin(angle) * r2), linePaint);
    }

    // ---------------------------------------------------------- 4: Speedometer

    private void drawSpeedometer(Canvas c, float w, float h, float fillFrac) {
        float cx = w / 2f, cy = h * 0.85f;
        float r = Math.min(w / 2f, h * 0.8f) * 0.9f;
        float sw = Math.max(dp(6), r * 0.16f);
        rect.set(cx - r, cy - r, cx + r, cy + r);
        arcPaint.setStrokeWidth(sw);
        arcPaint.setColor(Color.parseColor("#DED9CC"));
        c.drawArc(rect, 180, 180, false, arcPaint);
        arcPaint.setColor(ballColor);
        c.drawArc(rect, 180, 180 * fillFrac, false, arcPaint);

        double angle = Math.toRadians(180 + 180 * fillFrac);
        linePaint.setStrokeWidth(dp(3));
        linePaint.setColor(ballColor);
        c.drawLine(cx, cy, cx + (float) (Math.cos(angle) * r * 0.82f), cy + (float) (Math.sin(angle) * r * 0.82f), linePaint);
        fillPaint.setShader(null);
        fillPaint.setColor(ballColor);
        c.drawCircle(cx, cy, dp(5), fillPaint);
    }

    // ---------------------------------------------------------- 5: Minimal line

    private void drawMinimalLine(Canvas c, float w, float h, float fillFrac, float fillTop) {
        float cx = w / 2f, thinW = dp(6), left = cx - thinW / 2f, radius = thinW / 2f;
        rect.set(left, 0, left + thinW, h);
        c.drawRoundRect(rect, radius, radius, trackPaint);
        fillPaint.setShader(null);
        fillPaint.setColor(ballColor);
        rect.set(left, fillTop, left + thinW, h);
        c.drawRoundRect(rect, radius, radius, fillPaint);
        drawBall(c, cx, Math.max(dp(11), Math.min(h - dp(11), fillTop)));
    }

    // ---------------------------------------------------------- 6: Chunky LED

    private void drawChunky(Canvas c, float w, float h, float fillFrac) {
        float[] col = barColumn(w);
        float left = col[0], barW = col[1];
        int n = 14;
        float gap = dp(3);
        float blockH = (h - gap * (n - 1)) / n;
        int lit = Math.round(fillFrac * n);
        for (int i = 0; i < n; i++) {
            // i=0 is the bottom block.
            float bottom = h - i * (blockH + gap);
            float top = bottom - blockH;
            rect.set(left, top, left + barW, bottom);
            fillPaint.setShader(null);
            fillPaint.setColor(i < lit ? zoneColor((i + 1) / (float) n) : Color.parseColor("#DED9CC"));
            c.drawRoundRect(rect, dp(2), dp(2), fillPaint);
        }
    }

    // ---------------------------------------------------------- 7: Neumorphic

    private void drawNeumorphic(Canvas c, float w, float h, float fillFrac, float fillTop) {
        float[] col = barColumn(w);
        float left = col[0], barW = col[1], radius = barW / 2f;
        rect.set(left, 0, left + barW, h);
        fillPaint.setShader(null);
        fillPaint.setColor(Color.parseColor("#EDE8DA"));
        c.drawRoundRect(rect, radius, radius, fillPaint);

        float inset = dp(5);
        rect.set(left + inset, inset, left + barW - inset, h - inset);
        fillPaint.setColor(Color.parseColor("#E1DAC7"));
        c.drawRoundRect(rect, radius, radius, fillPaint);

        fillPaint.setColor(mixColor(Color.parseColor("#E1DAC7"), ballColor, 0.55f));
        c.save();
        c.clipRect(left + inset, fillTop, left + barW - inset, h - inset);
        c.drawRoundRect(rect, radius, radius, fillPaint);
        c.restore();

        linePaint.setStrokeWidth(dp(2));
        linePaint.setColor(ballColor);
        c.drawLine(left + inset, fillTop, left + barW - inset, fillTop, linePaint);
    }

    // ---------------------------------------------------------- 8: Glass

    private void drawGlass(Canvas c, float w, float h, float fillFrac, float fillTop) {
        float[] col = barColumn(w);
        float left = col[0], barW = col[1], radius = barW * 0.3f;
        rect.set(left, 0, left + barW, h);
        fillPaint.setShader(new android.graphics.LinearGradient(
                0, h, 0, 0, stopColors(), STOP_POS, Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, radius, radius, fillPaint);

        fillPaint.setShader(null);
        fillPaint.setColor(Color.argb(150, 255, 255, 255));
        c.save();
        c.clipRect(left, 0, left + barW, fillTop);
        c.drawRoundRect(rect, radius, radius, fillPaint);
        c.restore();

        drawBall(c, left + barW / 2f, Math.max(dp(11), Math.min(h - dp(11), fillTop)));
    }

    // ---------------------------------------------------------- 9: Dot column

    private void drawDots(Canvas c, float w, float h, float fillFrac) {
        float[] col = barColumn(w);
        float cx = col[0] + col[1] / 2f;
        int n = 10;
        float step = h / n;
        float r = Math.min(col[1], step) * 0.35f;
        int lit = Math.round(fillFrac * n);
        for (int i = 0; i < n; i++) {
            float cy = h - step * i - step / 2f;
            fillPaint.setShader(null);
            fillPaint.setColor(i < lit ? zoneColor((i + 1) / (float) n) : Color.parseColor("#DED9CC"));
            c.drawCircle(cx, cy, r, fillPaint);
        }
    }

    // ---------------------------------------------------------- 10: Ring

    private void drawRing(Canvas c, float w, float h, float fillFrac) {
        float d = Math.min(w, h) * 0.82f;
        float cx = w / 2f, cy = h / 2f;
        float sw = Math.max(dp(6), d * 0.11f);
        rect.set(cx - d / 2f, cy - d / 2f, cx + d / 2f, cy + d / 2f);
        arcPaint.setStrokeWidth(sw);
        arcPaint.setColor(Color.parseColor("#DED9CC"));
        c.drawArc(rect, -90, 360, false, arcPaint);
        arcPaint.setColor(ballColor);
        c.drawArc(rect, -90, 360 * fillFrac, false, arcPaint);
    }

    // ---------------------------------------------------------- 11: Thermometer

    private void drawThermometer(Canvas c, float w, float h, float fillFrac, float fillTop) {
        float cx = w / 2f;
        float bulbR = dp(12);
        float tubeW = dp(10);
        float tubeBottom = h - bulbR * 0.6f;
        float tubeTop = dp(4);
        rect.set(cx - tubeW / 2f, tubeTop, cx + tubeW / 2f, tubeBottom);
        c.drawRoundRect(rect, tubeW / 2f, tubeW / 2f, trackPaint);

        fillPaint.setShader(null);
        fillPaint.setColor(ballColor);
        float clampedFillTop = Math.min(Math.max(fillTop, tubeTop), tubeBottom);
        rect.set(cx - tubeW / 2f, clampedFillTop, cx + tubeW / 2f, tubeBottom);
        c.drawRoundRect(rect, tubeW / 2f, tubeW / 2f, fillPaint);

        c.drawCircle(cx, h - bulbR, bulbR, fillPaint);
    }

    // ---------------------------------------------------------- 12: Equalizer wave

    private static final float[] WAVE_HEIGHTS = {0.35f, 0.6f, 0.42f, 0.78f, 0.5f, 0.9f, 0.48f, 0.66f, 0.32f};

    private void drawWave(Canvas c, float w, float h, float fillFrac) {
        int n = WAVE_HEIGHTS.length;
        float useW = Math.min(w, dp(120));
        float left = (w - useW) / 2f;
        float gap = dp(3);
        float barW = (useW - gap * (n - 1)) / n;
        fillPaint.setShader(null);
        for (int i = 0; i < n; i++) {
            float barH = h * WAVE_HEIGHTS[i] * fillFrac / 0.75f; // 0.75 ~ typical mid reading, keeps bars visible at default volume
            barH = Math.min(h, barH);
            float x = left + i * (barW + gap);
            rect.set(x, h - barH, x + barW, h);
            fillPaint.setColor(barH > h * 0.6f ? Color.parseColor("#DC2626") : barH > h * 0.3f ? Color.parseColor("#F59E0B") : ballColor);
            c.drawRoundRect(rect, barW / 2f, barW / 2f, fillPaint);
        }
    }

    // ---------------------------------------------------------- 13: Icon header

    private void drawIconHeader(Canvas c, float w, float h, float fillFrac) {
        float cx = w / 2f, cy = h * 0.32f;
        float d = Math.min(w, h) * 0.42f * (0.85f + 0.15f * fillFrac);
        fillPaint.setShader(null);
        fillPaint.setColor(ballColor);
        c.drawCircle(cx, cy, d / 2f, fillPaint);

        fillPaint.setColor(Color.parseColor("#F4F1E8"));
        float s = d * 0.32f;
        Path speaker = new Path();
        speaker.moveTo(cx - s, cy - s * 0.5f);
        speaker.lineTo(cx - s * 0.3f, cy - s * 0.5f);
        speaker.lineTo(cx + s * 0.5f, cy - s);
        speaker.lineTo(cx + s * 0.5f, cy + s);
        speaker.lineTo(cx - s * 0.3f, cy + s * 0.5f);
        speaker.lineTo(cx - s, cy + s * 0.5f);
        speaker.close();
        c.drawPath(speaker, fillPaint);

        arcPaint.setStrokeWidth(dp(2.5f));
        arcPaint.setColor(Color.parseColor("#F4F1E8"));
        rect.set(cx + s * 0.7f - s, cy - s, cx + s * 0.7f + s, cy + s);
        c.drawArc(rect, -40, 80, false, arcPaint);

        // Below the icon, a plain track+fill bar so the value is still readable/draggable here.
        float[] col = barColumn(w);
        float left = col[0], barW = col[1], top = h * 0.58f;
        rect.set(left, top, left + barW, h);
        c.drawRoundRect(rect, barW / 2f, barW / 2f, trackPaint);
        float fillTop = top + (h - top) * (1f - fillFrac);
        c.save();
        c.clipRect(left, fillTop, left + barW, h);
        fillPaint.setColor(ballColor);
        c.drawRoundRect(rect, barW / 2f, barW / 2f, fillPaint);
        c.restore();
    }

    // ---------------------------------------------------------- 14: Compact pill

    private void drawCompactPill(Canvas c, float w, float h, float fillTop) {
        float cx = w / 2f, pillW = dp(18), left = cx - pillW / 2f, radius = pillW / 2f;
        rect.set(left, 0, left + pillW, h);
        c.drawRoundRect(rect, radius, radius, trackPaint);
        fillPaint.setShader(null);
        fillPaint.setColor(ballColor);
        c.save();
        c.clipRect(left, fillTop, left + pillW, h);
        c.drawRoundRect(rect, radius, radius, fillPaint);
        c.restore();
    }

    // ---------------------------------------------------------- 15: Retro LCD

    private void drawRetroLcd(Canvas c, float w, float h, float fillFrac) {
        float[] col = barColumn(w);
        float left = col[0], barW = col[1];
        rect.set(left, 0, left + barW, h);
        fillPaint.setShader(null);
        fillPaint.setColor(Color.parseColor("#1C2B1C"));
        c.drawRoundRect(rect, dp(6), dp(6), fillPaint);

        int n = 16;
        float pad = dp(6), gap = dp(2);
        float segH = (h - pad * 2 - gap * (n - 1)) / n;
        int lit = Math.round(fillFrac * n);
        for (int i = 0; i < n; i++) {
            float bottom = h - pad - i * (segH + gap);
            float top = bottom - segH;
            rect.set(left + pad, top, left + barW - pad, bottom);
            fillPaint.setColor(i < lit ? Color.parseColor("#8FE38F") : Color.parseColor("#294D29"));
            c.drawRect(rect, fillPaint);
        }
    }

    private static int mixColor(int a, int b, float t) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
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
