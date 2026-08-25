package com.huraiz.aodcontrol;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GestureSettingsActivity extends Activity implements ShizukuBridge.Listener {
    private static final int REQUEST_CAMERA = 2401;
    private static final int REQUEST_NOTIFICATIONS = 2402;

    private UiTheme.Palette colors;
    private String themeSignature;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Switch enabledSwitch;
    private TextView serviceStatus;
    private TextView inputStatus;
    private TextView edgeWidthValue;
    private TextView activeHeightValue;
    private TextView sensitivityValue;
    private TextView scopeValue;
    private GestureZonePreviewView gesturePreview;
    private final Map<String, TextView> actionLabels = new LinkedHashMap<>();

    private final Runnable statusTicker = new Runnable() {
        @Override public void run() {
            refreshStatus();
            main.postDelayed(this, 1200L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        UiTheme.applyActivityTheme(this);
        super.onCreate(savedInstanceState);
        colors = UiTheme.palette(this);
        themeSignature = UiTheme.signature(this);
        UiTheme.applyWindow(this, colors);
        setContentView(buildUi());
        ShizukuBridge.addListener(this);
        refreshAll();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!UiTheme.signature(this).equals(themeSignature)) {
            recreate();
            return;
        }
        AodGestureService.sync(this);
        refreshAll();
        main.removeCallbacks(statusTicker);
        main.post(statusTicker);
    }

    @Override protected void onPause() {
        main.removeCallbacks(statusTicker);
        super.onPause();
    }

    @Override protected void onDestroy() {
        ShizukuBridge.removeListener(this);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override public void onShizukuStateChanged() {
        runOnUiThread(() -> {
            AodGestureService.sync(this);
            refreshStatus();
        });
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

        TextView title = text("AOD gestures", 34, colors.text, false);
        title.setPadding(0, dp(8), 0, dp(6));
        root.addView(title);
        TextView subtitle = text("Use taps and swipes directly on the native AOD. No custom AOD screen is used.", 13, colors.muted, false);
        subtitle.setPadding(0, 0, 0, dp(24));
        root.addView(subtitle);

        root.addView(section("◉  Gesture monitor"));
        LinearLayout master = card();
        root.addView(master, matchWrap());

        enabledSwitch = addSwitchRow(master, "Enable AOD gestures",
                "Runs a lightweight Shizuku touch monitor only while this option is enabled.",
                AppPrefs.isGesturesEnabled(this), checked -> {
                    AppPrefs.setGesturesEnabled(this, checked);
                    if (checked && AppPrefs.isForegroundFallbackEnabled(this)) requestNotificationPermissionIfNeeded();
                    AodGestureService.sync(this);
                    refreshAll();
                });

        addDivider(master);
        scopeValue = addChoiceRow(master, "Gesture scope",
                "Choose whether configured gestures work on AOD, the visible lock screen, or both.",
                AppPrefs.gestureScopeLabel(AppPrefs.getGestureScope(this)),
                this::showScopePicker);

        addDivider(master);
        serviceStatus = text("", 13, colors.accent, true);
        serviceStatus.setPadding(dp(18), dp(13), dp(18), dp(13));
        master.addView(serviceStatus);

        addGap(root, 22);
        root.addView(section("▣  Gesture zones"));
        LinearLayout previewCard = card();
        root.addView(previewCard, matchWrap());
        gesturePreview = new GestureZonePreviewView(this);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(360));
        previewLp.setMargins(dp(8), dp(8), dp(8), dp(8));
        previewCard.addView(gesturePreview, previewLp);
        addDivider(previewCard);
        activeHeightValue = addSliderRow(previewCard, "Active gesture height",
                "Centered vertical area where gestures may start.",
                40, 100, AppPrefs.getGestureActiveHeightPercent(this), value -> {
                    AppPrefs.setGestureActiveHeightPercent(this, value);
                    AodGestureService.sync(this);
                    if (activeHeightValue != null) activeHeightValue.setText(value + "%");
                    if (gesturePreview != null) gesturePreview.invalidate();
                });
        addDivider(previewCard);
        edgeWidthValue = addSliderRow(previewCard, "Edge width",
                "Width of the left/right edge slide zones.",
                8, 35, AppPrefs.getGestureEdgeWidthPercent(this), value -> {
                    AppPrefs.setGestureEdgeWidthPercent(this, value);
                    AodGestureService.sync(this);
                    if (edgeWidthValue != null) edgeWidthValue.setText(value + "%");
                    if (gesturePreview != null) gesturePreview.invalidate();
                });
        addDivider(previewCard);
        sensitivityValue = addSliderRow(previewCard, "Gesture sensitivity",
                "Higher values accept shorter and less perfectly straight swipes.",
                1, 100, AppPrefs.getGestureSensitivityPercent(this), value -> {
                    AppPrefs.setGestureSensitivityPercent(this, value);
                    AodGestureService.sync(this);
                    if (sensitivityValue != null) sensitivityValue.setText(value + "%");
                });

        addGap(root, 22);
        root.addView(section("↕  Gestures"));
        LinearLayout gestureCard = card();
        root.addView(gestureCard, matchWrap());

        addGestureRow(gestureCard, AppPrefs.GESTURE_DOUBLE_TAP, "Double tap", "Two quick taps anywhere on AOD", false);
        addDivider(gestureCard);
        addGestureRow(gestureCard, AppPrefs.GESTURE_TRIPLE_TAP, "Triple tap", "Three quick taps anywhere on AOD", false);
        addDivider(gestureCard);
        addGestureRow(gestureCard, AppPrefs.GESTURE_SWIPE_LEFT_TO_RIGHT, "Swipe left → right", "Horizontal swipe across AOD", false);
        addDivider(gestureCard);
        addGestureRow(gestureCard, AppPrefs.GESTURE_SWIPE_RIGHT_TO_LEFT, "Swipe right → left", "Horizontal swipe across AOD", false);
        addDivider(gestureCard);
        addGestureRow(gestureCard, AppPrefs.GESTURE_SWIPE_UP, "Swipe up", "Vertical swipe upward", false);
        addDivider(gestureCard);
        addGestureRow(gestureCard, AppPrefs.GESTURE_SWIPE_DOWN, "Swipe down", "Vertical swipe downward", false);
        addDivider(gestureCard);
        addGestureRow(gestureCard, AppPrefs.GESTURE_LEFT_EDGE_UP, "Left edge ↑", "Slide upward in the configured left-edge zone", true);
        addDivider(gestureCard);
        addGestureRow(gestureCard, AppPrefs.GESTURE_LEFT_EDGE_DOWN, "Left edge ↓", "Slide downward in the configured left-edge zone", true);
        addDivider(gestureCard);
        addGestureRow(gestureCard, AppPrefs.GESTURE_RIGHT_EDGE_UP, "Right edge ↑", "Slide upward in the configured right-edge zone", true);
        addDivider(gestureCard);
        addGestureRow(gestureCard, AppPrefs.GESTURE_RIGHT_EDGE_DOWN, "Right edge ↓", "Slide downward in the configured right-edge zone", true);

        addGap(root, 22);
        root.addView(section("◇  Touch compatibility"));
        LinearLayout compatibility = card();
        root.addView(compatibility, matchWrap());

        TextView explain = text("AODControl observes the touchscreen through Shizuku's shell identity. Lock-screen scope works only while the phone is still locked; normal unlocked-screen touches are ignored.", 13, colors.muted, false);
        explain.setPadding(dp(18), dp(15), dp(18), dp(8));
        compatibility.addView(explain);

        addDivider(compatibility);
        addSwitchRow(compatibility, "AOD gesture keep-alive",
                "Keeps AOD gestures responsive when the touchscreen enters low-power mode. May increase battery usage while AOD is active.",
                AppPrefs.isGestureKeepAliveEnabled(this), checked -> {
                    AppPrefs.setGestureKeepAliveEnabled(this, checked);
                    AodGestureService.sync(this);
                });

        addDivider(compatibility);
        addSwitchRow(compatibility, "Pocket protection",
                "Ignore AOD and lock-screen gestures while the phone is covered/in a pocket to prevent accidental touches.",
                AppPrefs.isPocketProtectionEnabled(this), checked -> {
                    AppPrefs.setPocketProtectionEnabled(this, checked);
                    AodGestureService.sync(this);
                });

        addDivider(compatibility);
        inputStatus = text("Not checked", 13, colors.text, false);
        inputStatus.setPadding(dp(18), 0, dp(18), dp(9));
        compatibility.addView(inputStatus);

        Button check = button("Check AOD touch input");
        LinearLayout.LayoutParams checkLp = matchWrap();
        checkLp.setMargins(dp(14), 0, dp(14), dp(14));
        compatibility.addView(check, checkLp);
        check.setOnClickListener(v -> checkTouchInput());

        TextView note = text("AOD scope listens while the display is non-interactive. Lock-screen scope listens while the display is on and keyguard is still locked. Some OEMs may consume the same lock-screen gesture too, because AODControl observes input rather than blocking SystemUI.", 11, colors.muted, false);
        note.setPadding(dp(8), dp(18), dp(8), 0);
        root.addView(note);

        return scroll;
    }

    private void addGestureRow(LinearLayout parent, String gesture, String title, String subtitle, boolean edge) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(14), dp(14));

        LinearLayout copy = vertical();
        TextView titleView = text(title, 16, colors.text, false);
        copy.addView(titleView);
        TextView sub = text(subtitle, 12, colors.muted, false);
        sub.setPadding(0, dp(3), dp(8), 0);
        copy.addView(sub);
        row.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView action = text(AppPrefs.gestureActionLabel(AppPrefs.getGestureAction(this, gesture)), 13, colors.accent, true);
        action.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(action, new LinearLayout.LayoutParams(dp(135), LinearLayout.LayoutParams.WRAP_CONTENT));
        actionLabels.put(gesture, action);

        row.setOnClickListener(v -> showActionPicker(gesture, edge));
        parent.addView(row, matchWrap());
    }

    private void showActionPicker(String gesture, boolean edge) {
        int[] ids = edge
                ? new int[] {AppPrefs.GESTURE_ACTION_NONE, AppPrefs.GESTURE_ACTION_VOLUME_SLIDER,
                AppPrefs.GESTURE_ACTION_TORCH, AppPrefs.GESTURE_ACTION_PLAY_PAUSE,
                AppPrefs.GESTURE_ACTION_NEXT_TRACK, AppPrefs.GESTURE_ACTION_PREVIOUS_TRACK,
                AppPrefs.GESTURE_ACTION_VOLUME_UP, AppPrefs.GESTURE_ACTION_VOLUME_DOWN,
                AppPrefs.GESTURE_ACTION_TOGGLE_AOD, AppPrefs.GESTURE_ACTION_WAKE_AOD, AppPrefs.GESTURE_ACTION_SLEEP_AOD,
                AppPrefs.GESTURE_ACTION_WAKE_SCREEN}
                : new int[] {AppPrefs.GESTURE_ACTION_NONE, AppPrefs.GESTURE_ACTION_TORCH,
                AppPrefs.GESTURE_ACTION_PLAY_PAUSE, AppPrefs.GESTURE_ACTION_NEXT_TRACK,
                AppPrefs.GESTURE_ACTION_PREVIOUS_TRACK, AppPrefs.GESTURE_ACTION_VOLUME_UP,
                AppPrefs.GESTURE_ACTION_VOLUME_DOWN, AppPrefs.GESTURE_ACTION_TOGGLE_AOD,
                AppPrefs.GESTURE_ACTION_WAKE_AOD, AppPrefs.GESTURE_ACTION_SLEEP_AOD, AppPrefs.GESTURE_ACTION_WAKE_SCREEN};

        String[] labels = new String[ids.length];
        int current = AppPrefs.getGestureAction(this, gesture);
        int checked = 0;
        for (int i = 0; i < ids.length; i++) {
            labels[i] = AppPrefs.gestureActionLabel(ids[i]);
            if (ids[i] == current) checked = i;
        }

        new AlertDialog.Builder(this)
                .setTitle("Choose action")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    int action = ids[which];
                    AppPrefs.setGestureAction(this, gesture, action);
                    if (action == AppPrefs.GESTURE_ACTION_TORCH) requestCameraPermissionIfNeeded();
                    if (action == AppPrefs.GESTURE_ACTION_VOLUME_SLIDER) {
                        toast("Volume slider uses both directions on this edge");
                    }
                    AodGestureService.sync(this);
                    refreshAll();
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showScopePicker() {
        int[] values = new int[] {
                AppPrefs.GESTURE_SCOPE_AOD_ONLY,
                AppPrefs.GESTURE_SCOPE_LOCK_SCREEN_ONLY,
                AppPrefs.GESTURE_SCOPE_AOD_AND_LOCK_SCREEN
        };
        String[] labels = new String[] {"AOD only", "Lock screen only", "AOD + lock screen"};
        int current = AppPrefs.getGestureScope(this);
        int checked = current == AppPrefs.GESTURE_SCOPE_LOCK_SCREEN_ONLY ? 1
                : current == AppPrefs.GESTURE_SCOPE_AOD_AND_LOCK_SCREEN ? 2 : 0;
        new AlertDialog.Builder(this)
                .setTitle("Gesture scope")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    AppPrefs.setGestureScope(this, values[which]);
                    AodGestureService.sync(this);
                    refreshAll();
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private TextView addChoiceRow(LinearLayout parent, String title, String subtitle,
                                  String value, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(14), dp(14));

        LinearLayout copy = vertical();
        copy.addView(text(title, 16, colors.text, false));
        TextView sub = text(subtitle, 12, colors.muted, false);
        sub.setPadding(0, dp(3), dp(8), 0);
        copy.addView(sub);
        row.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView valueView = text(value, 13, colors.accent, true);
        valueView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(valueView, new LinearLayout.LayoutParams(dp(145), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(v -> onClick.run());
        parent.addView(row, matchWrap());
        return valueView;
    }

    private void checkTouchInput() {
        if (!ShizukuBridge.isReady()) {
            toast("Start Shizuku and grant AODControl access first");
            return;
        }
        inputStatus.setText("Checking…");
        io.execute(() -> {
            String result;
            try {
                IAodShellService shell = ShizukuBridge.getService();
                result = shell == null ? "Shizuku service unavailable" : shell.getTouchCapabilities();
            } catch (Throwable t) {
                result = "Touch check failed";
            }
            final String value = result;
            runOnUiThread(() -> inputStatus.setText(value));
        });
    }

    private void refreshAll() {
        if (enabledSwitch != null) enabledSwitch.setChecked(AppPrefs.isGesturesEnabled(this));
        for (Map.Entry<String, TextView> entry : actionLabels.entrySet()) {
            entry.getValue().setText(AppPrefs.gestureActionLabel(AppPrefs.getGestureAction(this, entry.getKey())));
        }
        if (activeHeightValue != null) activeHeightValue.setText(AppPrefs.getGestureActiveHeightPercent(this) + "%");
        if (edgeWidthValue != null) edgeWidthValue.setText(AppPrefs.getGestureEdgeWidthPercent(this) + "%");
        if (sensitivityValue != null) sensitivityValue.setText(AppPrefs.getGestureSensitivityPercent(this) + "%");
        if (scopeValue != null) scopeValue.setText(AppPrefs.gestureScopeLabel(AppPrefs.getGestureScope(this)));
        if (gesturePreview != null) gesturePreview.invalidate();
        refreshStatus();
    }

    private void refreshStatus() {
        if (serviceStatus != null) {
            String status = AodGestureService.statusText(this);
            if (AppPrefs.isForegroundFallbackEnabled(this) && AodGestureService.isRunning()) status += " • foreground service";
            serviceStatus.setText(status);
        }
    }

    private void requestCameraPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private interface ToggleListener { void onChanged(boolean checked); }
    private interface SliderListener { void onChanged(int value); }

    private TextView addSliderRow(LinearLayout parent, String title, String subtitle,
                                  int min, int max, int current, SliderListener listener) {
        LinearLayout host = vertical();
        host.setPadding(dp(18), dp(13), dp(18), dp(12));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = text(title, 15, colors.text, false);
        heading.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView valueView = text(current + "%", 13, colors.accent, true);
        valueView.setGravity(Gravity.END);
        heading.addView(valueView, new LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT));
        host.addView(heading, matchWrap());

        TextView sub = text(subtitle, 11, colors.muted, false);
        sub.setPadding(0, dp(3), 0, dp(4));
        host.addView(sub, matchWrap());

        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(Math.max(0, Math.min(max - min, current - min)));
        seek.setProgressTintList(ColorStateList.valueOf(colors.accent));
        seek.setThumbTintList(ColorStateList.valueOf(colors.accent));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = min + progress;
                valueView.setText(value + "%");
                if (fromUser) listener.onChanged(value);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        host.addView(seek, matchWrap());
        parent.addView(host, matchWrap());
        return valueView;
    }

    private Switch addSwitchRow(LinearLayout parent, String title, String subtitle,
                                boolean checked, ToggleListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(14), dp(14));

        LinearLayout copy = vertical();
        copy.addView(text(title, 17, colors.text, false));
        TextView sub = text(subtitle, 13, colors.muted, false);
        sub.setPadding(0, dp(3), dp(10), 0);
        copy.addView(sub);
        row.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        tintSwitch(toggle);
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> listener.onChanged(isChecked));
        row.addView(toggle);
        parent.addView(row, matchWrap());
        return toggle;
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

    private void tintSwitch(Switch toggle) {
        int[][] states = new int[][] {
                new int[] {android.R.attr.state_checked}, new int[] {}
        };
        toggle.setThumbTintList(new ColorStateList(states, new int[] {colors.accent, colors.muted}));
        toggle.setTrackTintList(new ColorStateList(states,
                new int[] {UiTheme.withAlpha(colors.accent, 125), UiTheme.withAlpha(colors.muted, 80)}));
    }

    private TextView section(String value) {
        TextView t = text(value, 15, colors.accent, true);
        t.setPadding(dp(4), 0, 0, dp(10));
        return t;
    }

    private LinearLayout card() {
        LinearLayout l = vertical();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colors.surface);
        bg.setCornerRadius(dp(20));
        l.setBackground(bg);
        return l;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
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

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}
