package com.huraiz.aodcontrol;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public class SettingsActivity extends Activity {
    private UiTheme.Palette colors;
    private String themeSignature;
    private LinearLayout appearanceRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        UiTheme.applyActivityTheme(this);
        super.onCreate(savedInstanceState);
        colors = UiTheme.palette(this);
        themeSignature = UiTheme.signature(this);
        UiTheme.applyWindow(this, colors);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        String now = UiTheme.signature(this);
        if (!now.equals(themeSignature)) recreate();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(colors.bg);
        applySystemBarInsets(scroll);

        LinearLayout root = vertical();
        root.setPadding(dp(20), dp(18), dp(20), dp(36));
        scroll.addView(root, matchWrap());

        TextView back = text("‹", 40, colors.text, false);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = text("General", 34, colors.text, false);
        title.setPadding(0, dp(8), 0, dp(28));
        root.addView(title);

        root.addView(section("◉  Appearance"));
        LinearLayout appearanceCard = card();
        root.addView(appearanceCard, matchWrap());

        appearanceRow = new LinearLayout(this);
        appearanceRow.setOrientation(LinearLayout.HORIZONTAL);
        appearanceRow.setPadding(dp(8), dp(10), dp(8), dp(12));
        appearanceCard.addView(appearanceRow, matchWrap());
        rebuildAppearanceButtons();

        addDivider(appearanceCard);
        addSwitchRow(appearanceCard, "Dynamic color",
                Build.VERSION.SDK_INT >= 31 ? "Use Material You colors provided by your device" : "Requires Android 12 or newer",
                AppPrefs.isDynamicColorEnabled(this), Build.VERSION.SDK_INT >= 31,
                checked -> {
                    AppPrefs.setDynamicColorEnabled(this, checked);
                    recreate();
                });

        addDivider(appearanceCard);
        addSwitchRow(appearanceCard, "Pure black theme", "Use pure black backgrounds in dark mode",
                AppPrefs.isPureBlackThemeEnabled(this), true,
                checked -> {
                    AppPrefs.setPureBlackThemeEnabled(this, checked);
                    recreate();
                });

        addGap(root, 22);
        root.addView(section("⚙  Background engine"));
        LinearLayout background = card();
        addSwitchRow(background, "Foreground fallback",
                "Off: Shizuku background with no persistent notification. On: Android foreground service with one compact AODControl active notification.",
                AppPrefs.isForegroundFallbackEnabled(this), true,
                checked -> {
                    AppPrefs.setForegroundFallbackEnabled(this, checked);
                    if (checked) ShizukuBackgroundEngine.stop();
                    AodGestureService.sync(this);
                    AutomationService.sync(this);
                });
        addDivider(background);
        background.addView(rowText("Current engine",
                AppPrefs.isForegroundFallbackEnabled(this)
                        ? "Foreground fallback • compact notification"
                        : "Shizuku background • no persistent notification"));
        root.addView(background, matchWrap());

        addGap(root, 22);
        root.addView(section("↕  AOD interaction"));
        LinearLayout gestures = card();
        TextView gestureTitle = text("AOD gestures", 17, colors.text, false);
        gestureTitle.setPadding(dp(18), dp(14), dp(18), dp(2));
        gestures.addView(gestureTitle);
        TextView gestureSub = text("Assign double/triple taps, swipes and edge slides to volume, torch and media actions while AOD is active.", 13, colors.muted, false);
        gestureSub.setPadding(dp(18), 0, dp(18), dp(10));
        gestures.addView(gestureSub);
        Button gestureButton = new Button(this);
        gestureButton.setAllCaps(false);
        gestureButton.setText("Configure AOD gestures");
        gestureButton.setTextColor(colors.text);
        GradientDrawable gestureBg = new GradientDrawable();
        gestureBg.setColor(colors.surfaceAlt);
        gestureBg.setCornerRadius(dp(16));
        gestureButton.setBackground(gestureBg);
        gestureButton.setOnClickListener(v -> startActivity(new android.content.Intent(this, GestureSettingsActivity.class)));
        LinearLayout.LayoutParams gestureLp = matchWrap();
        gestureLp.setMargins(dp(14), 0, dp(14), dp(14));
        gestures.addView(gestureButton, gestureLp);
        root.addView(gestures, matchWrap());

        addGap(root, 22);
        root.addView(section("ⓘ  About"));
        LinearLayout about = card();
        about.addView(rowText("AODControl", "Version 1.4.9"));
        addDivider(about);
        about.addView(rowText("Device", Build.MANUFACTURER + " " + Build.MODEL));
        addDivider(about);
        about.addView(rowText("Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"));
        root.addView(about, matchWrap());

        return scroll;
    }

    private void rebuildAppearanceButtons() {
        appearanceRow.removeAllViews();
        int current = AppPrefs.getAppearance(this);
        addAppearanceButton("System", AppPrefs.APPEARANCE_SYSTEM, current);
        addAppearanceButton("Light", AppPrefs.APPEARANCE_LIGHT, current);
        addAppearanceButton("Dark", AppPrefs.APPEARANCE_DARK, current);
    }

    private void addAppearanceButton(String label, int value, int current) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, value == current ? Typeface.BOLD : Typeface.NORMAL);
        button.setTextColor(value == current ? colors.onAccent : colors.text);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(value == current ? colors.accent : colors.surfaceAlt);
        bg.setCornerRadius(dp(18));
        button.setBackground(bg);
        button.setPadding(dp(8), dp(10), dp(8), dp(10));
        button.setOnClickListener(v -> {
            AppPrefs.setAppearance(this, value);
            recreate();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(62), 1f);
        lp.setMargins(dp(5), 0, dp(5), 0);
        appearanceRow.addView(button, lp);
    }

    private interface ToggleListener { void onChanged(boolean checked); }

    private void addSwitchRow(LinearLayout parent, String title, String subtitle,
                              boolean checked, boolean enabled, ToggleListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(14), dp(14));

        LinearLayout copy = vertical();
        TextView titleView = text(title, 17, enabled ? colors.text : colors.muted, false);
        copy.addView(titleView);
        TextView sub = text(subtitle, 13, colors.muted, false);
        sub.setPadding(0, dp(3), dp(10), 0);
        copy.addView(sub);
        row.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        toggle.setEnabled(enabled);
        tintSwitch(toggle);
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> listener.onChanged(isChecked));
        row.addView(toggle);
        parent.addView(row, matchWrap());
    }

    private TextView rowText(String title, String subtitle) {
        TextView v = text(title + "\n" + subtitle, 16, colors.text, false);
        v.setLineSpacing(dp(3), 1f);
        v.setPadding(dp(18), dp(14), dp(18), dp(14));
        return v;
    }

    private void tintSwitch(Switch toggle) {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        toggle.setThumbTintList(new ColorStateList(states,
                new int[]{colors.accent, colors.muted}));
        toggle.setTrackTintList(new ColorStateList(states,
                new int[]{UiTheme.withAlpha(colors.accent, 125), UiTheme.withAlpha(colors.muted, 80)}));
    }

    private TextView section(String value) {
        TextView t = text(value, 15, colors.accent, true);
        t.setPadding(dp(4), 0, 0, dp(10));
        return t;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout card() {
        LinearLayout l = vertical();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colors.surface);
        bg.setCornerRadius(dp(20));
        l.setBackground(bg);
        return l;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private void addDivider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(colors.divider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.leftMargin = dp(18);
        lp.rightMargin = dp(18);
        parent.addView(line, lp);
    }

    private void addGap(LinearLayout parent, int valueDp) {
        View gap = new View(this);
        parent.addView(gap, new LinearLayout.LayoutParams(1, dp(valueDp)));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void applySystemBarInsets(View view) {
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
        view.requestApplyInsets();
    }
}
