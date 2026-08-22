package com.huraiz.aodcontrol;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * AODControl's own full-screen ambient layout.
 *
 * This is a real TYPE_APPLICATION_OVERLAY window, so the permission requested
 * by Universal AOD is now actually used. The overlay is fully opaque black and
 * only renders the small AOD content above it.
 */
final class CustomAodOverlay {
    private final Context context;
    private final WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private LinearLayout content;
    private TextView time;
    private TextView date;
    private TextView battery;
    private boolean showing;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (!showing) return;
            updateClock();
            handler.postDelayed(this, 15000L);
        }
    };

    CustomAodOverlay(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean isShowing() {
        return showing;
    }

    boolean show() {
        if (showing) return true;
        if (windowManager == null || !UniversalAodService.hasOverlayPermission(context)) return false;

        root = buildUi();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.OPAQUE);

        lp.gravity = Gravity.TOP | Gravity.START;
        lp.screenBrightness = Math.max(0.01f, AppPrefs.getCustomAodBrightness(context) / 100f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }

        try {
            windowManager.addView(root, lp);
            showing = true;
            updateClock();
            handler.post(clockTick);
            return true;
        } catch (Throwable ignored) {
            clearViews();
            return false;
        }
    }

    void hide() {
        handler.removeCallbacksAndMessages(null);
        if (showing && root != null && windowManager != null) {
            try { windowManager.removeViewImmediate(root); } catch (Throwable ignored) {}
        }
        showing = false;
        clearViews();
    }

    private FrameLayout buildUi() {
        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Color.BLACK);

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);

        UiTheme.Palette palette = UiTheme.palette(context);
        time = text("--:--", 60, Color.rgb(244, 245, 238));
        time.setGravity(Gravity.CENTER);
        content.addView(time);

        date = text("", 16, palette.accent);
        date.setGravity(Gravity.CENTER);
        date.setPadding(0, dp(6), 0, 0);
        content.addView(date);

        battery = text("", 14, Color.rgb(175, 178, 166));
        battery.setGravity(Gravity.CENTER);
        battery.setPadding(0, dp(7), 0, 0);
        content.addView(battery);

        TextView footer = text("AODControl", 11, Color.rgb(105, 108, 99));
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(18), 0, 0);
        content.addView(footer);

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        frame.addView(content, cp);
        return frame;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setFontFeatureSettings("tnum");
        return view;
    }

    private void updateClock() {
        if (!showing || time == null) return;

        Date now = new Date();
        boolean is24 = DateFormat.is24HourFormat(context);
        time.setText(new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(now));

        Intent status = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = status == null ? -1 : status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = status == null ? 100 : status.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
        battery.setText(pct >= 0 ? pct + "%" : "");

        // Shift the whole cluster every minute to reduce OLED image retention.
        Calendar c = Calendar.getInstance();
        int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        content.setTranslationX(dp(((minute * 37) % 41) - 20));
        content.setTranslationY(dp(((minute * 53) % 61) - 30));
    }

    private void clearViews() {
        root = null;
        content = null;
        time = null;
        date = null;
        battery = null;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
