package com.huraiz.aodcontrol;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
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
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements ShizukuBridge.Listener {
    private static final int BG = Color.rgb(9, 9, 11);
    private static final int CARD = Color.rgb(24, 24, 27);
    private static final int CARD_ALT = Color.rgb(39, 39, 42);
    private static final int TEXT = Color.rgb(250, 250, 250);
    private static final int MUTED = Color.rgb(161, 161, 170);
    private static final int GOOD = Color.rgb(52, 211, 153);
    private static final int WARN = Color.rgb(251, 191, 36);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView shizukuStatus;
    private TextView shizukuDetail;
    private Button shizukuButton;
    private TextView currentState;
    private TextView currentReason;
    private TextView automationStatus;
    private TextView navigationSummary;

    private BehaviorEditor defaultEditor;
    private ModeEditor nightMode;
    private ModeEditor navigationMode;
    private ModeEditor outdoorMode;
    private ModeEditor chargingMode;
    private TimeRangeEditor nightTime;
    private TimeRangeEditor outdoorTime;
    private boolean notificationPermissionAsked;

    private final Runnable statusTicker = new Runnable() {
        @Override public void run() {
            refreshStatusUi();
            ui.postDelayed(this, 1500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        ShizukuBridge.addListener(this);
        refreshShizukuUi();
        AutomationService.sync(this);
        if (!AppPrefs.anyAutomationEnabled(this)) applyDefaultNow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshShizukuUi();
        refreshNavigationSummary();
        AutomationService.sync(this);
        ui.removeCallbacks(statusTicker);
        ui.post(statusTicker);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(statusTicker);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ShizukuBridge.removeListener(this);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onShizukuStateChanged() {
        runOnUiThread(() -> {
            refreshShizukuUi();
            if (ShizukuBridge.isReady()) {
                if (AppPrefs.anyAutomationEnabled(this)) AutomationService.refresh(this);
                else applyDefaultNow();
            }
        });
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        applySystemBarInsets(scroll);

        LinearLayout root = vertical();
        root.setPadding(dp(18), dp(22), dp(18), dp(34));
        scroll.addView(root, matchWrap());

        TextView title = text("AOD Control", 30, TEXT, true);
        root.addView(title);
        TextView subtitle = text("Always-on fingerprint, custom AOD opacity and automatic modes through Shizuku.", 14, MUTED, false);
        subtitle.setPadding(0, dp(5), 0, dp(16));
        root.addView(subtitle);

        buildShizukuCard(root);
        addGap(root, 12);
        buildCurrentStateCard(root);
        addGap(root, 14);

        TextView defaultHeading = text("Default phone state", 13, MUTED, true);
        defaultHeading.setPadding(dp(2), 0, 0, dp(8));
        root.addView(defaultHeading);

        LinearLayout defaultCard = card(CARD);
        defaultEditor = new BehaviorEditor(defaultCard, null, true);
        root.addView(defaultCard, cardParams());

        addGap(root, 16);
        TextView modesHeading = text("Modes", 13, MUTED, true);
        modesHeading.setPadding(dp(2), 0, 0, dp(3));
        root.addView(modesHeading);
        TextView modesHint = text("Enable only the modes you want. Active priority: Charging → Navigation → Outdoor → Night → Default.", 12, MUTED, false);
        modesHint.setPadding(dp(2), 0, dp(2), dp(10));
        root.addView(modesHint);

        nightMode = buildNightMode(root);
        addGap(root, 10);
        navigationMode = buildNavigationMode(root);
        addGap(root, 10);
        outdoorMode = buildOutdoorMode(root);
        addGap(root, 10);
        chargingMode = buildChargingMode(root);

        TextView footer = text("Automatic modes run a lightweight foreground service only while at least one mode is enabled. Navigation mode watches the foreground app through the restricted Shizuku service; it does not read navigation content.", 12, MUTED, false);
        footer.setPadding(dp(2), dp(12), dp(2), 0);
        root.addView(footer);

        return scroll;
    }

    private void buildShizukuCard(LinearLayout root) {
        LinearLayout shizukuCard = card(CARD);
        root.addView(shizukuCard, cardParams());
        shizukuStatus = text("Checking Shizuku…", 15, TEXT, true);
        shizukuCard.addView(shizukuStatus);
        shizukuDetail = text("", 12, MUTED, false);
        shizukuDetail.setPadding(0, dp(5), 0, dp(10));
        shizukuCard.addView(shizukuDetail);
        shizukuButton = button("Grant Shizuku access");
        shizukuButton.setOnClickListener(v -> {
            if (!ShizukuBridge.isBinderAlive()) {
                toast("Start Shizuku first, then return here");
                return;
            }
            ShizukuBridge.requestPermission(this);
        });
        shizukuCard.addView(shizukuButton, matchWrap());
    }

    private void buildCurrentStateCard(LinearLayout root) {
        LinearLayout stateCard = card(CARD);
        stateCard.addView(text("Current state", 13, MUTED, true));
        currentState = text("System default", 18, TEXT, true);
        currentState.setPadding(0, dp(5), 0, 0);
        stateCard.addView(currentState);
        currentReason = text("Reason: Default", 13, MUTED, false);
        currentReason.setPadding(0, dp(4), 0, 0);
        stateCard.addView(currentReason);
        automationStatus = text("Automation stopped", 12, MUTED, false);
        automationStatus.setPadding(0, dp(4), 0, 0);
        stateCard.addView(automationStatus);
        root.addView(stateCard, cardParams());
    }

    private ModeEditor buildNightMode(LinearLayout root) {
        LinearLayout host = card(CARD);
        ModeEditor editor = new ModeEditor(host, AppPrefs.MODE_NIGHT, "Night mode",
                "Use a different AOD state during your chosen night hours.");
        nightTime = new TimeRangeEditor(editor.details, AppPrefs.MODE_NIGHT, "Night hours");
        editor.attachBehaviorEditor();
        root.addView(host, cardParams());
        return editor;
    }

    private ModeEditor buildNavigationMode(LinearLayout root) {
        LinearLayout host = card(CARD);
        ModeEditor editor = new ModeEditor(host, AppPrefs.MODE_NAVIGATION, "Navigation mode",
                "Activates while one of your selected apps is the foreground app.");

        navigationSummary = text("No apps selected", 12, MUTED, false);
        navigationSummary.setPadding(0, dp(6), 0, dp(8));
        editor.details.addView(navigationSummary);
        Button chooseApps = button("Select navigation / ride apps");
        chooseApps.setOnClickListener(v -> showNavigationAppPicker());
        editor.details.addView(chooseApps, matchWrap());
        TextView navHint = text("Choose Google Maps, Waze, Uber, inDrive, Careem or any other installed app you want to use as a trigger.", 12, MUTED, false);
        navHint.setPadding(0, dp(7), 0, dp(8));
        editor.details.addView(navHint);

        editor.attachBehaviorEditor();
        root.addView(host, cardParams());
        return editor;
    }

    private ModeEditor buildOutdoorMode(LinearLayout root) {
        LinearLayout host = card(CARD);
        ModeEditor editor = new ModeEditor(host, AppPrefs.MODE_OUTDOOR, "Outdoor hours",
                "Use a different AOD state during a daytime/outdoor schedule.");
        outdoorTime = new TimeRangeEditor(editor.details, AppPrefs.MODE_OUTDOOR, "Outdoor hours");
        editor.attachBehaviorEditor();
        root.addView(host, cardParams());
        return editor;
    }

    private ModeEditor buildChargingMode(LinearLayout root) {
        LinearLayout host = card(CARD);
        ModeEditor editor = new ModeEditor(host, AppPrefs.MODE_CHARGING, "Charging mode",
                "Activates whenever the phone is plugged in or reports charging/full.");
        editor.attachBehaviorEditor();
        root.addView(host, cardParams());
        return editor;
    }

    private void onConfigurationChangedByUser() {
        if (AppPrefs.anyAutomationEnabled(this)) {
            requestNotificationPermissionIfNeeded();
            AutomationService.refresh(this);
        } else {
            AutomationService.sync(this);
            applyDefaultNow();
        }
        ui.postDelayed(this::refreshStatusUi, 350L);
    }

    private void applyDefaultNow() {
        if (AppPrefs.anyAutomationEnabled(this) || !ShizukuBridge.isReady()) return;
        AppPrefs.Behavior behavior = AppPrefs.getDefaultBehaviorConfig(this);
        io.execute(() -> {
            AodApplier.Result result = AodApplier.apply(this, behavior);
            AppPrefs.saveLastState(this, "Default", behavior, result.ok);
            runOnUiThread(this::refreshStatusUi);
        });
    }

    private void refreshStatusUi() {
        if (currentState == null) return;
        String state = AppPrefs.getLastState(this);
        String reason = AppPrefs.getLastReason(this);
        boolean ok = AppPrefs.getLastOk(this);
        currentState.setText(state);
        currentState.setTextColor(ok ? TEXT : WARN);
        currentReason.setText("Reason: " + reason);
        if (AppPrefs.anyAutomationEnabled(this)) {
            automationStatus.setText(AutomationService.isRunning() ? "Automation running" : "Automation starting…");
            automationStatus.setTextColor(AutomationService.isRunning() ? GOOD : MUTED);
        } else {
            automationStatus.setText("Automation stopped • no background service");
            automationStatus.setTextColor(MUTED);
        }
    }

    private void refreshShizukuUi() {
        if (shizukuStatus == null) return;
        if (!ShizukuBridge.isBinderAlive()) {
            shizukuStatus.setText("Shizuku is not running");
            shizukuStatus.setTextColor(WARN);
            shizukuDetail.setText("Start Shizuku. AOD Control will reconnect when you return.");
            shizukuButton.setText("Check Shizuku again");
            shizukuButton.setEnabled(true);
        } else if (!ShizukuBridge.isSupportedVersion()) {
            shizukuStatus.setText("Shizuku v11+ required");
            shizukuStatus.setTextColor(WARN);
            shizukuDetail.setText("Update Shizuku before using AOD Control.");
            shizukuButton.setText("Unsupported Shizuku version");
            shizukuButton.setEnabled(false);
        } else if (!ShizukuBridge.hasPermission()) {
            shizukuStatus.setText("Shizuku permission required");
            shizukuStatus.setTextColor(WARN);
            shizukuDetail.setText("Grant AOD Control access. No root is required.");
            shizukuButton.setText("Grant Shizuku access");
            shizukuButton.setEnabled(true);
        } else if (!ShizukuBridge.isReady()) {
            shizukuStatus.setText("Shizuku connecting…");
            shizukuStatus.setTextColor(TEXT);
            shizukuDetail.setText("Starting the restricted AOD settings service.");
            shizukuButton.setText("Connecting…");
            shizukuButton.setEnabled(false);
            ShizukuBridge.bindIfPossible();
        } else {
            shizukuStatus.setText("Shizuku connected ✓");
            shizukuStatus.setTextColor(GOOD);
            shizukuDetail.setText("Ready to apply AOD settings and detect selected foreground apps.");
            shizukuButton.setText("Ready");
            shizukuButton.setEnabled(false);
        }
    }

    private void showNavigationAppPicker() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved;
        try {
            resolved = pm.queryIntentActivities(launcher, 0);
        } catch (Throwable t) {
            toast("Could not read installed apps");
            return;
        }

        Map<String, String> labelsByPackage = new LinkedHashMap<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null || info.activityInfo.packageName == null) continue;
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            CharSequence labelCs = info.loadLabel(pm);
            String label = labelCs == null ? pkg : labelCs.toString();
            labelsByPackage.put(pkg, label);
        }

        List<AppChoice> choices = new ArrayList<>();
        for (Map.Entry<String, String> entry : labelsByPackage.entrySet()) {
            choices.add(new AppChoice(entry.getKey(), entry.getValue()));
        }
        Collections.sort(choices, Comparator.comparing(a -> a.label.toLowerCase(Locale.getDefault())));
        if (choices.isEmpty()) {
            toast("No launchable apps found");
            return;
        }

        Set<String> selected = AppPrefs.getNavigationPackages(this);
        String[] labels = new String[choices.size()];
        boolean[] checked = new boolean[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            AppChoice choice = choices.get(i);
            labels[i] = choice.label;
            checked[i] = selected.contains(choice.packageName);
        }

        new AlertDialog.Builder(this)
                .setTitle("Navigation mode apps")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Clear", (dialog, which) -> {
                    AppPrefs.setNavigationPackages(this, Collections.emptySet());
                    refreshNavigationSummary();
                    onConfigurationChangedByUser();
                })
                .setPositiveButton("Save", (dialog, which) -> {
                    Set<String> packages = new HashSet<>();
                    for (int i = 0; i < choices.size(); i++) {
                        if (checked[i]) packages.add(choices.get(i).packageName);
                    }
                    AppPrefs.setNavigationPackages(this, packages);
                    refreshNavigationSummary();
                    onConfigurationChangedByUser();
                })
                .show();
    }

    private void refreshNavigationSummary() {
        if (navigationSummary == null) return;
        Set<String> selected = AppPrefs.getNavigationPackages(this);
        if (selected.isEmpty()) {
            navigationSummary.setText("No apps selected");
            navigationSummary.setTextColor(WARN);
            return;
        }
        PackageManager pm = getPackageManager();
        List<String> names = new ArrayList<>();
        for (String pkg : selected) {
            try {
                CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                names.add(label == null ? pkg : label.toString());
            } catch (Throwable ignored) {
                names.add(pkg);
            }
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        StringBuilder b = new StringBuilder();
        int limit = Math.min(4, names.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) b.append(", ");
            b.append(names.get(i));
        }
        if (names.size() > limit) b.append(" +").append(names.size() - limit).append(" more");
        navigationSummary.setText("Selected: " + b);
        navigationSummary.setTextColor(TEXT);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && !notificationPermissionAsked
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionAsked = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 601);
        }
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

    private final class ModeEditor {
        final String mode;
        final CheckBox enabled;
        final LinearLayout details;
        BehaviorEditor behaviorEditor;

        ModeEditor(LinearLayout host, String mode, String title, String description) {
            this.mode = mode;
            enabled = new CheckBox(MainActivity.this);
            enabled.setText(title);
            enabled.setTextColor(TEXT);
            enabled.setTextSize(17);
            enabled.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            enabled.setChecked(AppPrefs.isModeEnabled(MainActivity.this, mode));
            host.addView(enabled, matchWrap());

            TextView desc = text(description, 12, MUTED, false);
            desc.setPadding(dp(4), 0, 0, dp(5));
            host.addView(desc);

            details = vertical();
            details.setPadding(dp(4), dp(5), dp(4), 0);
            host.addView(details, matchWrap());
            details.setVisibility(enabled.isChecked() ? View.VISIBLE : View.GONE);

            enabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                AppPrefs.setModeEnabled(MainActivity.this, mode, isChecked);
                details.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                onConfigurationChangedByUser();
            });
        }

        void attachBehaviorEditor() {
            behaviorEditor = new BehaviorEditor(details, mode, false);
        }
    }

    private final class BehaviorEditor {
        final String mode;
        final boolean isDefault;
        final RadioGroup group;
        final RadioButton system;
        final RadioButton aofp;
        final RadioButton manual;
        final LinearLayout manualPanel;
        final SeekBar opacity;
        final TextView opacityValue;
        boolean suppress;

        BehaviorEditor(LinearLayout parent, String mode, boolean isDefault) {
            this.mode = mode;
            this.isDefault = isDefault;

            TextView label = text(isDefault ? "Behavior" : "When this mode is active", 13, MUTED, true);
            label.setPadding(0, dp(3), 0, dp(4));
            parent.addView(label);

            group = new RadioGroup(MainActivity.this);
            group.setOrientation(RadioGroup.VERTICAL);
            system = behaviorRadio("System default");
            aofp = behaviorRadio("AOFP • AOD blank");
            manual = behaviorRadio("Manual opacity");
            group.addView(system);
            group.addView(aofp);
            group.addView(manual);
            parent.addView(group, matchWrap());

            manualPanel = vertical();
            manualPanel.setPadding(dp(4), dp(3), dp(4), dp(6));
            opacityValue = text("100%", 22, TEXT, true);
            manualPanel.addView(opacityValue);
            opacity = new SeekBar(MainActivity.this);
            opacity.setMax(100);
            manualPanel.addView(opacity, matchWrap());
            TextView hint = text("0% = normal AOD • 100% = completely black", 11, MUTED, false);
            hint.setGravity(Gravity.CENTER_HORIZONTAL);
            manualPanel.addView(hint);
            parent.addView(manualPanel, matchWrap());

            int behavior = isDefault ? AppPrefs.getDefaultBehavior(MainActivity.this)
                    : AppPrefs.getModeBehavior(MainActivity.this, mode);
            int raw = isDefault ? AppPrefs.getDefaultOpacity(MainActivity.this)
                    : AppPrefs.getModeOpacity(MainActivity.this, mode);
            opacity.setProgress(rawToPercent(raw));
            opacityValue.setText(rawToPercent(raw) + "%");
            setCheckedBehavior(behavior);
            manualPanel.setVisibility(behavior == AppPrefs.BEHAVIOR_MANUAL ? View.VISIBLE : View.GONE);

            group.setOnCheckedChangeListener((g, checkedId) -> {
                if (suppress) return;
                int selected = behaviorForId(checkedId);
                if (isDefault) AppPrefs.setDefaultBehavior(MainActivity.this, selected);
                else AppPrefs.setModeBehavior(MainActivity.this, mode, selected);
                manualPanel.setVisibility(selected == AppPrefs.BEHAVIOR_MANUAL ? View.VISIBLE : View.GONE);
                onConfigurationChangedByUser();
            });

            opacity.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    opacityValue.setText(progress + "%");
                }

                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                    int value = percentToRaw(seekBar.getProgress());
                    if (BehaviorEditor.this.isDefault) AppPrefs.setDefaultOpacity(MainActivity.this, value);
                    else AppPrefs.setModeOpacity(MainActivity.this, BehaviorEditor.this.mode, value);
                    onConfigurationChangedByUser();
                }
            });
        }

        private void setCheckedBehavior(int behavior) {
            suppress = true;
            if (behavior == AppPrefs.BEHAVIOR_AOFP) aofp.setChecked(true);
            else if (behavior == AppPrefs.BEHAVIOR_MANUAL) manual.setChecked(true);
            else system.setChecked(true);
            suppress = false;
        }

        private int behaviorForId(int id) {
            if (id == aofp.getId()) return AppPrefs.BEHAVIOR_AOFP;
            if (id == manual.getId()) return AppPrefs.BEHAVIOR_MANUAL;
            return AppPrefs.BEHAVIOR_SYSTEM;
        }
    }

    private final class TimeRangeEditor {
        final String mode;
        final Button start;
        final Button end;

        TimeRangeEditor(LinearLayout parent, String mode, String title) {
            this.mode = mode;
            TextView heading = text(title, 13, MUTED, true);
            heading.setPadding(0, dp(3), 0, dp(5));
            parent.addView(heading);

            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            start = button("Start " + formatMinutes(AppPrefs.getStartMinutes(MainActivity.this, mode)));
            end = button("End " + formatMinutes(AppPrefs.getEndMinutes(MainActivity.this, mode)));
            LinearLayout.LayoutParams half1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            half1.rightMargin = dp(5);
            LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            half2.leftMargin = dp(5);
            row.addView(start, half1);
            row.addView(end, half2);
            parent.addView(row, matchWrap());
            addGap(parent, 8);

            start.setOnClickListener(v -> pickTime(true));
            end.setOnClickListener(v -> pickTime(false));
        }

        private void pickTime(boolean isStart) {
            int minutes = isStart ? AppPrefs.getStartMinutes(MainActivity.this, mode)
                    : AppPrefs.getEndMinutes(MainActivity.this, mode);
            int hour = minutes / 60;
            int minute = minutes % 60;
            new TimePickerDialog(MainActivity.this, (view, h, m) -> {
                int startMinutes = AppPrefs.getStartMinutes(MainActivity.this, mode);
                int endMinutes = AppPrefs.getEndMinutes(MainActivity.this, mode);
                if (isStart) startMinutes = h * 60 + m;
                else endMinutes = h * 60 + m;
                AppPrefs.setTimeRange(MainActivity.this, mode, startMinutes, endMinutes);
                refreshLabels();
                onConfigurationChangedByUser();
            }, hour, minute, false).show();
        }

        private void refreshLabels() {
            start.setText("Start " + formatMinutes(AppPrefs.getStartMinutes(MainActivity.this, mode)));
            end.setText("End " + formatMinutes(AppPrefs.getEndMinutes(MainActivity.this, mode)));
        }
    }

    private RadioButton behaviorRadio(String label) {
        RadioButton radio = new RadioButton(this);
        radio.setId(View.generateViewId());
        radio.setText(label);
        radio.setTextColor(TEXT);
        radio.setTextSize(15);
        radio.setPadding(0, dp(3), 0, dp(3));
        return radio;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout card(int color) {
        LinearLayout l = vertical();
        l.setPadding(dp(15), dp(15), dp(15), dp(15));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(18));
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

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD_ALT);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        return b;
    }

    private LinearLayout.LayoutParams cardParams() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void addGap(LinearLayout parent, int valueDp) {
        View gap = new View(this);
        parent.addView(gap, new LinearLayout.LayoutParams(1, dp(valueDp)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private static int rawToPercent(int raw) {
        raw = Math.max(0, Math.min(255, raw));
        return Math.round(raw * 100f / 255f);
    }

    private static int percentToRaw(int percent) {
        percent = Math.max(0, Math.min(100, percent));
        return Math.round(percent * 255f / 100f);
    }

    private static String formatMinutes(int minutes) {
        minutes = Math.max(0, Math.min(1439, minutes));
        int hour = minutes / 60;
        int minute = minutes % 60;
        int displayHour = hour % 12;
        if (displayHour == 0) displayHour = 12;
        return String.format(Locale.US, "%d:%02d %s", displayHour, minute, hour < 12 ? "AM" : "PM");
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }

    private static final class AppChoice {
        final String packageName;
        final String label;

        AppChoice(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }
}
