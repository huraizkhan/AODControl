package com.huraiz.aodcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Visual map of the touch regions currently used by native-AOD gestures. */
public final class GestureZonePreviewView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final UiTheme.Palette colors;

    public GestureZonePreviewView(Context context) {
        super(context);
        colors = UiTheme.palette(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(2));
        stroke.setColor(colors.accent);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) width = Math.round(dp(280));
        int desired = Math.min(Math.round(dp(420)), Math.round(width * 1.22f));
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(desired, heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = dp(14);
        RectF phone = new RectF(pad, pad, getWidth() - pad, getHeight() - pad);
        float radius = dp(28);

        fill.setColor(colors.surfaceAlt);
        canvas.drawRoundRect(phone, radius, radius, fill);
        canvas.drawRoundRect(phone, radius, radius, stroke);

        int activeHeightPct = AppPrefs.getGestureActiveHeightPercent(getContext());
        float inactivePart = (100f - activeHeightPct) / 200f;
        RectF active = new RectF(phone.left,
                phone.top + phone.height() * inactivePart,
                phone.right,
                phone.bottom - phone.height() * inactivePart);

        // Dim the parts outside the configured active start area.
        fill.setColor(UiTheme.withAlpha(colors.muted, colors.dark ? 42 : 28));
        if (active.top > phone.top) {
            canvas.drawRect(phone.left, phone.top, phone.right, active.top, fill);
            canvas.drawRect(phone.left, active.bottom, phone.right, phone.bottom, fill);
        }

        float edgeFraction = AppPrefs.getGestureEdgeWidthPercent(getContext()) / 100f;
        float edge = phone.width() * edgeFraction;
        fill.setColor(UiTheme.withAlpha(colors.accent, colors.dark ? 42 : 30));
        canvas.drawRect(active.left, active.top, active.left + edge, active.bottom, fill);
        canvas.drawRect(active.right - edge, active.top, active.right, active.bottom, fill);

        text.setColor(colors.accent);
        text.setTextSize(dp(16));
        canvas.drawText("↑", phone.left + edge / 2f, active.centerY() - dp(22), text);
        canvas.drawText("↓", phone.left + edge / 2f, active.centerY() + dp(38), text);
        canvas.drawText("↑", phone.right - edge / 2f, active.centerY() - dp(22), text);
        canvas.drawText("↓", phone.right - edge / 2f, active.centerY() + dp(38), text);

        text.setTextSize(dp(10));
        canvas.drawText("LEFT", phone.left + edge / 2f, active.top + dp(24), text);
        canvas.drawText("RIGHT", phone.right - edge / 2f, active.top + dp(24), text);

        text.setColor(colors.text);
        text.setTextSize(dp(18));
        canvas.drawText("←   SWIPE   →", active.centerX(), active.centerY() - dp(26), text);
        canvas.drawText("↑     ↓", active.centerX(), active.centerY() + dp(18), text);

        text.setColor(colors.muted);
        text.setTextSize(dp(11));
        canvas.drawText("Tap / swipe start area: " + activeHeightPct + "%", phone.centerX(), phone.bottom - dp(38), text);
        canvas.drawText("Edge width: " + AppPrefs.getGestureEdgeWidthPercent(getContext()) + "%", phone.centerX(), phone.bottom - dp(18), text);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
