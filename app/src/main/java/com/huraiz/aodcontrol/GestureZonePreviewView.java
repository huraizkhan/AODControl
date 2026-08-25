package com.huraiz.aodcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Simple visual map of the touch regions used by native-AOD gestures. */
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

        float edge = phone.width() * 0.20f;
        fill.setColor(UiTheme.withAlpha(colors.accent, colors.dark ? 38 : 26));
        canvas.drawRoundRect(new RectF(phone.left, phone.top, phone.left + edge, phone.bottom), radius, radius, fill);
        canvas.drawRoundRect(new RectF(phone.right - edge, phone.top, phone.right, phone.bottom), radius, radius, fill);

        text.setColor(colors.accent);
        text.setTextSize(dp(16));
        canvas.drawText("↑", phone.left + edge / 2f, phone.centerY() - dp(22), text);
        canvas.drawText("↓", phone.left + edge / 2f, phone.centerY() + dp(38), text);
        canvas.drawText("↑", phone.right - edge / 2f, phone.centerY() - dp(22), text);
        canvas.drawText("↓", phone.right - edge / 2f, phone.centerY() + dp(38), text);

        text.setTextSize(dp(11));
        canvas.drawText("LEFT EDGE", phone.left + edge / 2f, phone.top + dp(30), text);
        canvas.drawText("RIGHT EDGE", phone.right - edge / 2f, phone.top + dp(30), text);

        text.setColor(colors.text);
        text.setTextSize(dp(18));
        canvas.drawText("←   SWIPE   →", phone.centerX(), phone.centerY() - dp(26), text);
        canvas.drawText("↑     ↓", phone.centerX(), phone.centerY() + dp(18), text);

        text.setColor(colors.muted);
        text.setTextSize(dp(12));
        canvas.drawText("Double / triple tap anywhere", phone.centerX(), phone.bottom - dp(42), text);
        canvas.drawText("Edge zone = outer 20%", phone.centerX(), phone.bottom - dp(20), text);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
