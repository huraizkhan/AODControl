package com.huraiz.aodcontrol;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UniversalAodSettingsActivity extends Activity implements ShizukuBridge.Listener {
    private UiTheme.Palette colors;
    private String themeSignature;
    private LinearLayout displayButtons;
    private LinearLayout modeButtons;
    private LinearLayout temporaryHost;
    private TextView detectedStatus;
    private TextView engineStatus;
    private TextView durationValue;
    private TextView brightnessValue;
    private Switch universalSwitch;
    private Switch lcdSwitch;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle savedInstanceState) {
        UiTheme.applyActivityTheme(this);
        super.onCreate(savedInstanceState);
        colors = UiTheme.palette(this);
        themeSignature = UiTheme.signature(this);
        UiTheme.applyWindow(this, colors);
        setContentView(buildUi());
        ShizukuBridge.addListener(this);
        refreshUiState();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!UiTheme.signature(this).equals(themeSignature)) {
            recreate();
            return;
        }
        UniversalAodService.sync(this);
        refreshUiState();
    }

    @Override protected void onDestroy() {
        ShizukuBridge.removeListener(this);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override public void onShizukuStateChanged() {
        runOnUiThread(this::refreshUiState);
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

        TextView title = text("Universal AOD", 34, colors.text, false);
        title.setPadding(0, dp(8), 0, dp(6));
        root.addView(title);
        TextView sub = text("Fallback AOD for phones without native AOD, or after a time-limited native AOD turns off.", 13, colors.muted, false);
        sub.setPadding(0, 0, 0, dp(24));
        root.addView(sub);

        root.addView(section("◉  Engine"));
        LinearLayout engineCard = card();
        root.addView(engineCard, matchWrap());

        addSwitchRow(engineCard, "Universal AOD fallback",
                "Waits for native AOD first. Takes over only when the display actually reaches screen-off.",
                AppPrefs.isUniversalAodEnabled(this), true, checked -> {
                    AppPrefs.setUniversalAodEnabled(this, checked);
                    if (checked && !UniversalAodService.hasOverlayPermission(this)) openOverlayPermission();
                    UniversalAodService.sync(this);
                    refreshUiState();
                }, sw -> universalSwitch = sw);

        addDivider(engineCard);
        engineStatus = text("", 13, colors.muted, false);
        engineStatus.setPadding(dp(18), dp(13), dp(18), dp(13));
        engineCard.addView(engineStatus);

        addGap(root, 22);
        root.addView(section("▣  Display technology"));
        LinearLayout displayCard = card();
        root.addView(displayCard, matchWrap());

        TextView hint = text("Auto uses safe capability hints when available. You can override it if the phone reports nothing useful.", 13, colors.muted, false);
        hint.setPadding(dp(18), dp(15), dp(18), dp(10));
        displayCard.addView(hint);

        displayButtons = new LinearLayout(this);
        displayButtons.setOrientation(LinearLayout.HORIZONTAL);
        displayButtons.setPadding(dp(10), dp(4), dp(10), dp(12));
        displayCard.addView(displayButtons, matchWrap());
        rebuildDisplayButtons();

        addDivider(displayCard);
        detectedStatus = text("Detected: Unknown", 14, colors.text, false);
        detectedStatus.setPadding(dp(18), dp(14), dp(18), dp(8));
        displayCard.addView(detectedStatus);

        Button detect = button("Detect display type");
        LinearLayout.LayoutParams detectLp = matchWrap();
        detectLp.setMargins(dp(14), 0, dp(14), dp(12));
        displayCard.addView(detect, detectLp);
        detect.setOnClickListener(v -> detectPanel());

        addDivider(displayCard);
        addSwitchRow(displayCard, "Allow AOD on LCD",
                "Off by default. LCD keeps its backlight on even with a black AOD. Temporary mode is recommended.",
                AppPrefs.isLcdAodAllowed(this), true, checked -> {
                    AppPrefs.setLcdAodAllowed(this, checked);
                    UniversalAodService.sync(this);
                    refreshUiState();
                }, sw -> lcdSwitch = sw);

        addGap(root, 22);
        root.addView(section("◷  Custom AOD behavior"));
        LinearLayout behaviorCard = card();
        root.addView(behaviorCard, matchWrap());

        TextView modeHint = text("Temporary is the safe default for LCD. Continuous is best suited to OLED/AMOLED when native AOD is unavailable.", 13, colors.muted, false);
        modeHint.setPadding(dp(18), dp(15), dp(18), dp(10));
        behaviorCard.addView(modeHint);

        modeButtons = new LinearLayout(this);
        modeButtons.setOrientation(LinearLayout.HORIZONTAL);
        modeButtons.setPadding(dp(10), dp(4), dp(10), dp(12));
        behaviorCard.addView(modeButtons, matchWrap());
        rebuildModeButtons();

        temporaryHost = vertical();
        behaviorCard.addView(temporaryHost, matchWrap());
        rebuildTemporaryControls();

        addDivider(behaviorCard);
        LinearLayout brightnessHeader = horizontalRow();
        TextView brightnessTitle = text("AOD brightness", 16, colors.text, false);
        brightnessHeader.addView(brightnessTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        brightnessValue = text(AppPrefs.getCustomAodBrightness(this) + "%", 14, colors.accent, true);
        brightnessHeader.addView(brightnessValue);
        behaviorCard.addView(brightnessHeader, matchWrap());

        SeekBar brightness = new SeekBar(this);
        brightness.setMax(19);
        brightness.setProgress(AppPrefs.getCustomAodBrightness(this) - 1);
        tintSeek(brightness);
        LinearLayout.LayoutParams seekLp = matchWrap();
        seekLp.setMargins(dp(12), 0, dp(12), dp(10));
        behaviorCard.addView(brightness, seekLp);
        brightness.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int pct = progress + 1;
                brightnessValue.setText(pct + "%");
                if (fromUser) AppPrefs.setCustomAodBrightness(UniversalAodSettingsActivity.this, pct);
            }
        });

        addGap(root, 22);
        root.addView(section("◇  Required access"));
        LinearLayout access = card();
        root.addView(access, matchWrap());

        TextView overlayExplain = text("Display over other apps lets AODControl safely start its custom lock-screen AOD from the background. Native AOFP controls still use Shizuku separately.", 13, colors.muted, false);
        overlayExplain.setPadding(dp(18), dp(15), dp(18), dp(10));
        access.addView(overlayExplain);
        Button overlayButton = button("Open Display over other apps");
        LinearLayout.LayoutParams overlayLp = matchWrap();
        overlayLp.setMargins(dp(14), 0, dp(14), dp(14));
        access.addView(overlayButton, overlayLp);
        overlayButton.setOnClickListener(v -> openOverlayPermission());

        TextView footer = text("Custom AOD is a compatibility fallback, not an OEM low-power display mode. On OLED it keeps black pixels off, but the display pipeline is still awake.", 11, colors.muted, false);
        footer.setPadding(dp(8), dp(18), dp(8), 0);
        root.addView(footer);

        return scroll;
    }

    private void detectPanel() {
        if (!ShizukuBridge.isReady()) {
            toast("Start Shizuku first for automatic panel detection");
            return;
        }
        detectedStatus.setText("Detecting…");
        io.execute(() -> {
            int result = AppPrefs.DISPLAY_UNKNOWN;
            try {
                IAodShellService shell = ShizukuBridge.getService();
                if (shell != null) result = DisplayTechnology.detectFromHints(shell.getDisplayPanelHints());
            } catch (Throwable ignored) {}
            final int detected = result;
            AppPrefs.setDetectedDisplayTechnology(this, detected);
            runOnUiThread(() -> {
                refreshUiState();
                UniversalAodService.sync(this);
            });
        });
    }

    private void refreshUiState() {
        if (engineStatus != null) {
            String status = UniversalAodService.statusText(this);
            if (UniversalAodService.isRunning()) status += " • service running";
            engineStatus.setText(status);
            engineStatus.setTextColor(status.startsWith("Ready") ? colors.good : colors.muted);
        }
        if (detectedStatus != null) {
            int detected = AppPrefs.getDetectedDisplayTechnology(this);
            int resolved = AppPrefs.getResolvedDisplayTechnology(this);
            String value = "Detected: " + AppPrefs.displayTechnologyLabel(detected);
            if (AppPrefs.getDisplayTechnology(this) != AppPrefs.DISPLAY_AUTO) {
                value += "  •  Using manual: " + AppPrefs.displayTechnologyLabel(resolved);
            }
            detectedStatus.setText(value);
        }
        if (universalSwitch != null) universalSwitch.setChecked(AppPrefs.isUniversalAodEnabled(this));
        if (lcdSwitch != null) lcdSwitch.setChecked(AppPrefs.isLcdAodAllowed(this));
        rebuildDisplayButtons();
        rebuildModeButtons();
    }

    private void rebuildDisplayButtons() {
        if (displayButtons == null) return;
        displayButtons.removeAllViews();
        int current = AppPrefs.getDisplayTechnology(this);
        addSegment(displayButtons, "Auto", AppPrefs.DISPLAY_AUTO, current, value -> {
            AppPrefs.setDisplayTechnology(this, value);
            UniversalAodService.sync(this);
            refreshUiState();
        });
        addSegment(displayButtons, "OLED", AppPrefs.DISPLAY_OLED, current, value -> {
            AppPrefs.setDisplayTechnology(this, value);
            UniversalAodService.sync(this);
            refreshUiState();
        });
        addSegment(displayButtons, "LCD", AppPrefs.DISPLAY_LCD, current, value -> {
            AppPrefs.setDisplayTechnology(this, value);
            UniversalAodService.sync(this);
            refreshUiState();
        });
    }

    private void rebuildModeButtons() {
        if (modeButtons == null) return;
        modeButtons.removeAllViews();
        int current = AppPrefs.getCustomAodMode(this);
        addSegment(modeButtons, "Temporary", AppPrefs.CUSTOM_AOD_TEMPORARY, current, value -> {
            AppPrefs.setCustomAodMode(this, value);
            rebuildModeButtons();
            rebuildTemporaryControls();
            UniversalAodService.sync(this);
        });
        addSegment(modeButtons, "Continuous", AppPrefs.CUSTOM_AOD_CONTINUOUS, current, value -> {
            AppPrefs.setCustomAodMode(this, value);
            rebuildModeButtons();
            rebuildTemporaryControls();
            UniversalAodService.sync(this);
        });
    }

    private void rebuildTemporaryControls() {
        if (temporaryHost == null) return;
        temporaryHost.removeAllViews();
        if (AppPrefs.getCustomAodMode(this) != AppPrefs.CUSTOM_AOD_TEMPORARY) return;

        addDivider(temporaryHost);
        LinearLayout row = horizontalRow();
        TextView title = text("Visible time", 16, colors.text, false);
        row.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        durationValue = text(AppPrefs.getCustomAodSeconds(this) + " sec", 14, colors.accent, true);
        row.addView(durationValue);
        temporaryHost.addView(row, matchWrap());

        SeekBar duration = new SeekBar(this);
        duration.setMax(55);
        duration.setProgress(AppPrefs.getCustomAodSeconds(this) - 5);
        tintSeek(duration);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(dp(12), 0, dp(12), dp(10));
        temporaryHost.addView(duration, lp);
        duration.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int seconds = progress + 5;
                durationValue.setText(seconds + " sec");
                if (fromUser) AppPrefs.setCustomAodSeconds(UniversalAodSettingsActivity.this, seconds);
            }
        });
    }

    private interface ValueListener { void onSelected(int value); }
    private interface ToggleListener { void onChanged(boolean checked); }
    private interface SwitchCapture { void capture(Switch sw); }

    private void addSegment(LinearLayout parent, String label, int value, int current, ValueListener listener) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, value == current ? Typeface.BOLD : Typeface.NORMAL);
        button.setTextColor(value == current ? colors.onAccent : colors.text);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(value == current ? colors.accent : colors.surfaceAlt);
        bg.setCornerRadius(dp(17));
        button.setBackground(bg);
        button.setOnClickListener(v -> listener.onSelected(value));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        parent.addView(button, lp);
    }

    private void addSwitchRow(LinearLayout parent, String title, String subtitle,
                              boolean checked, boolean enabled, ToggleListener listener, SwitchCapture capture) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(14), dp(14));

        LinearLayout copy = vertical();
        copy.addView(text(title, 17, enabled ? colors.text : colors.muted, false));
        TextView sub = text(subtitle, 13, colors.muted, false);
        sub.setPadding(0, dp(3), dp(10), 0);
        copy.addView(sub);
        row.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        toggle.setEnabled(enabled);
        tintSwitch(toggle);
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> listener.onChanged(isChecked));
        capture.capture(toggle);
        row.addView(toggle);
        parent.addView(row, matchWrap());
    }

    private void openOverlayPermission() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            toast("Open Settings → Apps → Special app access → Display over other apps");
        }
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(13), dp(18), dp(8));
        return row;
    }

    private void tintSwitch(Switch toggle) {
        int[][] states = new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}};
        toggle.setThumbTintList(new ColorStateList(states, new int[]{colors.accent, colors.muted}));
        toggle.setTrackTintList(new ColorStateList(states,
                new int[]{UiTheme.withAlpha(colors.accent, 125), UiTheme.withAlpha(colors.muted, 80)}));
    }

    private void tintSeek(SeekBar seek) {
        seek.setProgressTintList(ColorStateList.valueOf(colors.accent));
        seek.setThumbTintList(ColorStateList.valueOf(colors.accent));
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

    private Button button(String label) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(14);
        b.setTextColor(colors.text);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colors.surfaceAlt);
        bg.setCornerRadius(dp(16));
        b.setBackground(bg);
        return b;
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
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(valueDp)));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
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

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
