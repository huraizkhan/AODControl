package com.huraiz.aodcontrol;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class CustomAodActivity extends Activity {
    private static WeakReference<CustomAodActivity> visible = new WeakReference<>(null);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView time;
    private TextView date;
    private TextView battery;
    private boolean temporaryFinished;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            updateClock();
            handler.postDelayed(this, 15000L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        visible = new WeakReference<>(this);
        UniversalAodService.markCustomVisible(true);

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowManager.LayoutParams params = window.getAttributes();
        params.screenBrightness = Math.max(0.01f, AppPrefs.getCustomAodBrightness(this) / 100f);
        window.setAttributes(params);

        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            window.getInsetsController().hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }

        setContentView(buildUi());
        handler.post(clockTick);

        if (AppPrefs.getCustomAodMode(this) == AppPrefs.CUSTOM_AOD_TEMPORARY) {
            handler.postDelayed(this::finishTemporary, AppPrefs.getCustomAodSeconds(this) * 1000L);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        UniversalAodService.markCustomVisible(true);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        UniversalAodService.markCustomVisible(false);
        CustomAodActivity current = visible.get();
        if (current == this) visible.clear();
        super.onDestroy();
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);

        UiTheme.Palette palette = UiTheme.palette(this);
        time = text("--:--", 58, Color.rgb(244, 245, 238));
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

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(content, lp);
        return root;
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
        Date now = new Date();
        boolean is24 = DateFormat.is24HourFormat(this);
        time.setText(new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(now));

        Intent status = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = status == null ? -1 : status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = status == null ? 100 : status.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
        battery.setText(pct >= 0 ? pct + "%" : "");

        // Small deterministic movement once per minute to reduce OLED image retention.
        Calendar c = Calendar.getInstance();
        int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        content.setTranslationX(dp(((minute * 37) % 31) - 15));
        content.setTranslationY(dp(((minute * 53) % 41) - 20));
    }

    private void finishTemporary() {
        if (temporaryFinished || isFinishing()) return;
        temporaryFinished = true;
        UniversalAodService.markTemporaryComplete(this);
        finishAndRemoveTask();
        overridePendingTransition(0, 0);
    }

    static void finishVisible() {
        CustomAodActivity activity = visible.get();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing()) {
                activity.finishAndRemoveTask();
                activity.overridePendingTransition(0, 0);
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
