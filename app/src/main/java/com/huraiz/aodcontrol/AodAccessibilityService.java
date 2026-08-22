package com.huraiz.aodcontrol;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
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
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Optional lock-screen compatibility layer for OEMs that keep third-party
 * Activities and TYPE_APPLICATION_OVERLAY windows behind keyguard.
 *
 * This service deliberately does not inspect accessibility events or window
 * content. It only uses TYPE_ACCESSIBILITY_OVERLAY to render AODControl's own
 * ambient screen when UniversalAodService asks it to.
 */
public final class AodAccessibilityService extends AccessibilityService {
    private static WeakReference<AodAccessibilityService> active = new WeakReference<>(null);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
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

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        active = new WeakReference<>(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        UniversalAodService.sync(this);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Intentionally unused. AODControl does not read accessibility events.
    }

    @Override public void onInterrupt() {
        // Nothing to interrupt: no spoken feedback or screen inspection is used.
    }

    @Override public void onDestroy() {
        hideAodInternal();
        AodAccessibilityService current = active.get();
        if (current == this) active.clear();
        UniversalAodService.sync(this);
        super.onDestroy();
    }

    static boolean isConnected() {
        return active.get() != null;
    }

    static boolean isEnabled(Context context) {
        try {
            AccessibilityManager manager = (AccessibilityManager)
                    context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (manager == null) return false;
            List<AccessibilityServiceInfo> enabled = manager.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (enabled == null) return false;
            for (AccessibilityServiceInfo info : enabled) {
                if (info == null || info.getResolveInfo() == null
                        || info.getResolveInfo().serviceInfo == null) continue;
                if (context.getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)
                        && AodAccessibilityService.class.getName().equals(
                        info.getResolveInfo().serviceInfo.name)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    static boolean showAod() {
        AodAccessibilityService service = active.get();
        return service != null && service.showAodInternal();
    }

    static void hideAod() {
        AodAccessibilityService service = active.get();
        if (service != null) service.hideAodInternal();
    }

    private boolean showAodInternal() {
        if (showing) return true;
        if (windowManager == null) windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) return false;

        root = buildUi();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.screenBrightness = Math.max(0.01f, AppPrefs.getCustomAodBrightness(this) / 100f);
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

    private void hideAodInternal() {
        handler.removeCallbacksAndMessages(null);
        if (showing && root != null && windowManager != null) {
            try { windowManager.removeViewImmediate(root); } catch (Throwable ignored) {}
        }
        showing = false;
        clearViews();
    }

    private FrameLayout buildUi() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        frame.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);

        UiTheme.Palette palette = UiTheme.palette(this);
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
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setFontFeatureSettings("tnum");
        return view;
    }

    private void updateClock() {
        if (!showing || time == null) return;
        Date now = new Date();
        boolean is24 = DateFormat.is24HourFormat(this);
        time.setText(new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(now));

        Intent status = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = status == null ? -1 : status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = status == null ? 100 : status.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
        battery.setText(pct >= 0 ? pct + "%" : "");

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
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
