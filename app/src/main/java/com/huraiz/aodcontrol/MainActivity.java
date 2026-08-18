package com.huraiz.aodcontrol;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
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

    private TextView shizukuStatus;
    private TextView shizukuDetail;
    private Button shizukuButton;
    private RadioGroup modeGroup;
    private RadioButton alwaysRadio;
    private RadioButton manualRadio;
    private RadioButton smartRadio;
    private LinearLayout alwaysDetails;
    private LinearLayout manualDetails;
    private LinearLayout smartDetails;
    private SeekBar manualOpacity;
    private TextView manualOpacityValue;
    private CheckBox sameSettings;
    private LinearLayout sharedProfileHost;
    private LinearLayout separateProfilesHost;
    private ProfileEditor sharedProfileEditor;
    private ProfileEditor screenProfileEditor;
    private ProfileEditor fingerprintProfileEditor;
    private TextView calibrationStatus;
    private TextView touchCapabilityStatus;
    private TextView serviceStatus;
    private Button calibrateButton;
    private boolean suppressMode;
    private boolean suppressSame;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        ShizukuBridge.addListener(this);
        restoreModeSelection();
        refreshModeVisibility();
        refreshShizukuUi();
        refreshCalibrationUi();

        if (AppPrefs.getMode(this) == AppPrefs.MODE_SMART) {
            requestNotificationPermissionIfNeeded();
            try { SmartDimmingService.start(this); } catch (Throwable ignored) {}
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshShizukuUi();
        refreshCalibrationUi();
        refreshServiceStatus();
        if (AppPrefs.getMode(this) == AppPrefs.MODE_SMART && !SmartDimmingService.isRunning()) {
            try { SmartDimmingService.start(this); } catch (Throwable ignored) {}
        }
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
                applyCurrentMode();
                queryTouchCapabilities();
            }
        });
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = vertical();
        root.setPadding(dp(18), dp(22), dp(18), dp(34));
        scroll.addView(root, matchWrap());

        TextView title = text("AOD Control", 30, TEXT, true);
        root.addView(title);
        TextView subtitle = text("AOD visibility and screen-off fingerprint control through Shizuku.", 14, MUTED, false);
        subtitle.setPadding(0, dp(5), 0, dp(16));
        root.addView(subtitle);

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

        addGap(root, 14);
        TextView choose = text("Mode", 13, MUTED, true);
        choose.setPadding(dp(2), 0, 0, dp(8));
        root.addView(choose);

        LinearLayout modeCard = card(CARD);
        root.addView(modeCard, cardParams());
        modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.VERTICAL);
        alwaysRadio = modeRadio("1. Always-on Fingerprint");
        manualRadio = modeRadio("2. Manual AOD Opacity");
        smartRadio = modeRadio("3. Smart Dimming Mode");
        modeGroup.addView(alwaysRadio);
        modeGroup.addView(manualRadio);
        modeGroup.addView(smartRadio);
        modeCard.addView(modeGroup, matchWrap());

        Button restoreDefaults = button("Restore system AOD defaults");
        LinearLayout.LayoutParams restoreParams = matchWrap();
        restoreParams.topMargin = dp(10);
        modeCard.addView(restoreDefaults, restoreParams);
        restoreDefaults.setOnClickListener(v -> restoreSystemDefaults());

        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (suppressMode) return;
            int mode = modeFromCheckedId(checkedId);
            AppPrefs.setMode(this, mode);
            refreshModeVisibility();
            onModeChanged(mode);
        });

        addGap(root, 12);
        alwaysDetails = card(CARD);
        TextView configured = text("Configured for always-on fingerprint.", 15, GOOD, true);
        alwaysDetails.addView(configured);
        root.addView(alwaysDetails, cardParams());

        manualDetails = buildManualCard();
        root.addView(manualDetails, cardParams());

        smartDetails = buildSmartCard();
        root.addView(smartDetails, cardParams());

        TextView footer = text("AOD Control never reads fingerprint templates or authentication data. UDFPS triggering only watches touch coordinates around the calibrated sensor area while Smart Dimming is active.", 12, MUTED, false);
        footer.setPadding(dp(2), dp(12), dp(2), 0);
        root.addView(footer);

        return scroll;
    }

    private LinearLayout buildManualCard() {
        LinearLayout card = card(CARD);
        card.addView(text("AOD opacity", 16, TEXT, true));
        manualOpacityValue = text("100%", 30, TEXT, true);
        manualOpacityValue.setPadding(0, dp(8), 0, 0);
        card.addView(manualOpacityValue);

        manualOpacity = new SeekBar(this);
        manualOpacity.setMax(100);
        int percent = rawToPercent(AppPrefs.getManualOpacity(this));
        manualOpacity.setProgress(percent);
        manualOpacityValue.setText(percent + "%");
        card.addView(manualOpacity, matchWrap());

        TextView hint = text("0% = normal AOD • 100% = completely black", 12, MUTED, false);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        hint.setPadding(0, dp(4), 0, 0);
        card.addView(hint);

        manualOpacity.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                manualOpacityValue.setText(progress + "%");
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int raw = percentToRaw(seekBar.getProgress());
                AppPrefs.setManualOpacity(MainActivity.this, raw);
                applyCurrentMode();
            }
        });
        return card;
    }

    private LinearLayout buildSmartCard() {
        LinearLayout card = card(CARD);
        card.addView(text("Smart Dimming", 18, TEXT, true));

        serviceStatus = text("Service stopped", 12, MUTED, false);
        serviceStatus.setPadding(0, dp(4), 0, dp(12));
        card.addView(serviceStatus);

        sameSettings = new CheckBox(this);
        sameSettings.setText("Use same settings for Screen Off & Fingerprint");
        sameSettings.setTextColor(TEXT);
        sameSettings.setTextSize(14);
        sameSettings.setChecked(AppPrefs.useSameTriggerSettings(this));
        card.addView(sameSettings, matchWrap());

        sharedProfileHost = vertical();
        sharedProfileEditor = new ProfileEditor("Screen Off + Fingerprint", AppPrefs.PROFILE_SHARED);
        sharedProfileHost.addView(sharedProfileEditor.root, matchWrap());
        card.addView(sharedProfileHost, matchWrap());

        separateProfilesHost = vertical();
        screenProfileEditor = new ProfileEditor("Screen Off", AppPrefs.PROFILE_SCREEN);
        fingerprintProfileEditor = new ProfileEditor("Fingerprint", AppPrefs.PROFILE_FINGERPRINT);
        separateProfilesHost.addView(screenProfileEditor.root, matchWrap());
        addGap(separateProfilesHost, 10);
        separateProfilesHost.addView(fingerprintProfileEditor.root, matchWrap());
        card.addView(separateProfilesHost, matchWrap());

        sameSettings.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressSame) return;
            boolean old = AppPrefs.useSameTriggerSettings(this);
            if (old == isChecked) return;
            if (!isChecked) {
                DimmingProfile shared = AppPrefs.getProfile(this, AppPrefs.PROFILE_SHARED);
                AppPrefs.saveProfile(this, AppPrefs.PROFILE_SCREEN, shared);
                AppPrefs.saveProfile(this, AppPrefs.PROFILE_FINGERPRINT, shared);
                screenProfileEditor.load();
                fingerprintProfileEditor.load();
            } else {
                DimmingProfile screen = AppPrefs.getProfile(this, AppPrefs.PROFILE_SCREEN);
                AppPrefs.saveProfile(this, AppPrefs.PROFILE_SHARED, screen);
                sharedProfileEditor.load();
            }
            AppPrefs.setUseSameTriggerSettings(this, isChecked);
            refreshSharedSeparateVisibility();
            refreshSmartService();
        });

        addGap(card, 14);
        LinearLayout calibration = card(CARD_ALT);
        calibration.addView(text("Fingerprint trigger", 15, TEXT, true));
        calibrationStatus = text("Not calibrated", 12, MUTED, false);
        calibrationStatus.setPadding(0, dp(4), 0, dp(5));
        calibration.addView(calibrationStatus);
        touchCapabilityStatus = text("Input monitor: checking…", 12, MUTED, false);
        touchCapabilityStatus.setPadding(0, 0, 0, dp(10));
        calibration.addView(touchCapabilityStatus);
        calibrateButton = button("Calibrate UDFPS area");
        calibrateButton.setOnClickListener(v -> startActivity(new Intent(this, CalibrationActivity.class)));
        calibration.addView(calibrateButton, matchWrap());
        TextView fpHint = text("For under-display fingerprint sensors. Screen-off dimming still works if this device does not expose readable touch input to Shizuku shell.", 11, MUTED, false);
        fpHint.setPadding(0, dp(9), 0, 0);
        calibration.addView(fpHint);
        card.addView(calibration, matchWrap());

        refreshSharedSeparateVisibility();
        return card;
    }

    private void restoreModeSelection() {
        suppressMode = true;
        int mode = AppPrefs.getMode(this);
        if (mode == AppPrefs.MODE_ALWAYS_FINGERPRINT) alwaysRadio.setChecked(true);
        else if (mode == AppPrefs.MODE_MANUAL) manualRadio.setChecked(true);
        else if (mode == AppPrefs.MODE_SMART) smartRadio.setChecked(true);
        else modeGroup.clearCheck();
        suppressMode = false;
    }

    private int modeFromCheckedId(int checkedId) {
        if (checkedId == alwaysRadio.getId()) return AppPrefs.MODE_ALWAYS_FINGERPRINT;
        if (checkedId == manualRadio.getId()) return AppPrefs.MODE_MANUAL;
        if (checkedId == smartRadio.getId()) return AppPrefs.MODE_SMART;
        return AppPrefs.MODE_NONE;
    }

    private void refreshModeVisibility() {
        int mode = AppPrefs.getMode(this);
        alwaysDetails.setVisibility(mode == AppPrefs.MODE_ALWAYS_FINGERPRINT ? View.VISIBLE : View.GONE);
        manualDetails.setVisibility(mode == AppPrefs.MODE_MANUAL ? View.VISIBLE : View.GONE);
        smartDetails.setVisibility(mode == AppPrefs.MODE_SMART ? View.VISIBLE : View.GONE);
        refreshServiceStatus();
    }

    private void onModeChanged(int mode) {
        if (mode == AppPrefs.MODE_SMART) {
            requestNotificationPermissionIfNeeded();
            try { SmartDimmingService.start(this); } catch (Throwable t) { toast("Could not start Smart Dimming service"); }
        } else {
            SmartDimmingService.stop(this);
            applyCurrentMode();
        }

        if (mode != AppPrefs.MODE_NONE && !ShizukuBridge.hasPermission() && ShizukuBridge.isBinderAlive()) {
            ShizukuBridge.requestPermission(this);
        }
        refreshServiceStatus();
    }

    private void restoreSystemDefaults() {
        IAodShellService shell = ShizukuBridge.getService();
        if (shell == null) {
            if (!ShizukuBridge.isBinderAlive()) {
                toast("Start Shizuku first, then tap Restore again");
            } else if (!ShizukuBridge.hasPermission()) {
                ShizukuBridge.requestPermission(this);
                toast("Grant Shizuku access, then tap Restore again");
            } else {
                toast("Shizuku is still connecting");
            }
            return;
        }

        AppPrefs.setMode(this, AppPrefs.MODE_NONE);
        SmartDimmingService.stop(this);
        suppressMode = true;
        modeGroup.clearCheck();
        suppressMode = false;
        refreshModeVisibility();

        io.execute(() -> {
            boolean ok = true;
            try {
                String a = shell.removeDimmingOverride();
                String b = shell.deleteSetting("secure", AodSettings.DOZE_ALWAYS_ON);
                ok = (a == null || a.isEmpty()) && (b == null || b.isEmpty());
            } catch (Throwable ignored) {
                ok = false;
            }
            final boolean restored = ok;
            runOnUiThread(() -> toast(restored
                    ? "System AOD defaults restored"
                    : "Restore was incomplete; check Shizuku and try again"));
        });
    }

    private void applyCurrentMode() {
        int mode = AppPrefs.getMode(this);
        if (mode == AppPrefs.MODE_NONE) return;
        if (mode == AppPrefs.MODE_SMART) {
            refreshSmartService();
            return;
        }

        IAodShellService shell = ShizukuBridge.getService();
        if (shell == null) return;
        io.execute(() -> {
            try {
                shell.putSetting("secure", AodSettings.DOZE_ALWAYS_ON, "1");
                if (mode == AppPrefs.MODE_ALWAYS_FINGERPRINT) {
                    shell.setUniformDimming(255, 0);
                } else if (mode == AppPrefs.MODE_MANUAL) {
                    shell.setUniformDimming(AppPrefs.getManualOpacity(this), 0);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private void refreshSmartService() {
        if (AppPrefs.getMode(this) != AppPrefs.MODE_SMART) return;
        try { SmartDimmingService.refresh(this); } catch (Throwable ignored) {}
        refreshServiceStatus();
    }

    private void refreshShizukuUi() {
        if (!ShizukuBridge.isBinderAlive()) {
            shizukuStatus.setText("Shizuku is not running");
            shizukuStatus.setTextColor(WARN);
            shizukuDetail.setText("Start Shizuku. The app will reconnect automatically.");
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
            shizukuDetail.setText("Grant AOD Control access. No root or WRITE_SECURE_SETTINGS ADB grant is needed.");
            shizukuButton.setText("Grant Shizuku access");
            shizukuButton.setEnabled(true);
        } else if (!ShizukuBridge.isReady()) {
            shizukuStatus.setText("Shizuku connecting…");
            shizukuStatus.setTextColor(TEXT);
            shizukuDetail.setText("Starting the restricted AOD shell service.");
            shizukuButton.setText("Connecting…");
            shizukuButton.setEnabled(false);
            ShizukuBridge.bindIfPossible();
        } else {
            shizukuStatus.setText("Shizuku connected ✓");
            shizukuStatus.setTextColor(GOOD);
            shizukuDetail.setText("AOD settings are controlled with shell identity only while Shizuku is available.");
            shizukuButton.setText("Ready");
            shizukuButton.setEnabled(false);
        }
    }

    private void refreshCalibrationUi() {
        if (calibrationStatus == null) return;
        if (AppPrefs.isFingerprintCalibrated(this)) {
            calibrationStatus.setText(String.format(Locale.US, "Calibrated at %.0f%% × %.0f%% of the display",
                    AppPrefs.fingerprintX(this) * 100f, AppPrefs.fingerprintY(this) * 100f));
            calibrationStatus.setTextColor(GOOD);
            calibrateButton.setText("Recalibrate UDFPS area");
            if (AppPrefs.getMode(this) == AppPrefs.MODE_SMART) refreshSmartService();
        } else {
            calibrationStatus.setText("Not calibrated");
            calibrationStatus.setTextColor(MUTED);
            calibrateButton.setText("Calibrate UDFPS area");
        }
        queryTouchCapabilities();
    }

    private void queryTouchCapabilities() {
        if (touchCapabilityStatus == null) return;
        IAodShellService shell = ShizukuBridge.getService();
        if (shell == null) {
            touchCapabilityStatus.setText("Input monitor: waiting for Shizuku");
            return;
        }
        io.execute(() -> {
            String value;
            try { value = shell.getTouchCapabilities(); }
            catch (Throwable e) { value = "Unavailable"; }
            final String result = value;
            runOnUiThread(() -> touchCapabilityStatus.setText("Input monitor: " + result));
        });
    }

    private void refreshServiceStatus() {
        if (serviceStatus == null) return;
        if (AppPrefs.getMode(this) != AppPrefs.MODE_SMART) {
            serviceStatus.setText("Service stopped");
            serviceStatus.setTextColor(MUTED);
        } else if (SmartDimmingService.isRunning()) {
            serviceStatus.setText("Background service running • low activity while idle");
            serviceStatus.setTextColor(GOOD);
        } else {
            serviceStatus.setText("Starting background service…");
            serviceStatus.setTextColor(WARN);
        }
    }

    private void refreshSharedSeparateVisibility() {
        if (sharedProfileHost == null || separateProfilesHost == null) return;
        boolean same = AppPrefs.useSameTriggerSettings(this);
        suppressSame = true;
        sameSettings.setChecked(same);
        suppressSame = false;
        sharedProfileHost.setVisibility(same ? View.VISIBLE : View.GONE);
        separateProfilesHost.setVisibility(same ? View.GONE : View.VISIBLE);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 711);
        }
    }

    private RadioButton modeRadio(String label) {
        RadioButton radio = new RadioButton(this);
        radio.setId(View.generateViewId());
        radio.setText(label);
        radio.setTextColor(TEXT);
        radio.setTextSize(16);
        radio.setPadding(0, dp(5), 0, dp(5));
        return radio;
    }

    private final class ProfileEditor {
        final String prefix;
        final LinearLayout root;
        final TextView visibleValue;
        final TextView delayValue;
        final SeekBar visibleSeek;
        final SeekBar delaySeek;
        final RadioGroup opacityMode;
        final RadioButton autoRadio;
        final RadioButton manualRadio;
        final LinearLayout manualOpacityHost;
        final TextView startValue;
        final TextView endValue;
        final SeekBar startSeek;
        final SeekBar endSeek;
        boolean suppress;

        ProfileEditor(String title, String prefix) {
            this.prefix = prefix;
            root = card(CARD_ALT);
            root.addView(text(title, 15, TEXT, true));

            visibleValue = text("", 13, TEXT, true);
            visibleValue.setPadding(0, dp(10), 0, 0);
            root.addView(visibleValue);
            visibleSeek = new SeekBar(MainActivity.this);
            visibleSeek.setMax(59);
            root.addView(visibleSeek, matchWrap());

            delayValue = text("", 13, TEXT, true);
            delayValue.setPadding(0, dp(7), 0, 0);
            root.addView(delayValue);
            delaySeek = new SeekBar(MainActivity.this);
            root.addView(delaySeek, matchWrap());

            TextView opacityLabel = text("Opacity mode", 13, TEXT, true);
            opacityLabel.setPadding(0, dp(8), 0, dp(4));
            root.addView(opacityLabel);
            opacityMode = new RadioGroup(MainActivity.this);
            opacityMode.setOrientation(RadioGroup.HORIZONTAL);
            autoRadio = new RadioButton(MainActivity.this);
            autoRadio.setId(View.generateViewId());
            autoRadio.setText("Auto");
            autoRadio.setTextColor(TEXT);
            manualRadio = new RadioButton(MainActivity.this);
            manualRadio.setId(View.generateViewId());
            manualRadio.setText("Manual");
            manualRadio.setTextColor(TEXT);
            opacityMode.addView(autoRadio);
            opacityMode.addView(manualRadio);
            root.addView(opacityMode, matchWrap());

            TextView autoHint = text("Auto starts from the device's own AOD ambient brightness/scrim curve, then fades toward black.", 11, MUTED, false);
            autoHint.setPadding(0, dp(2), 0, dp(7));
            root.addView(autoHint);

            manualOpacityHost = vertical();
            startValue = text("", 12, TEXT, true);
            manualOpacityHost.addView(startValue);
            startSeek = new SeekBar(MainActivity.this);
            startSeek.setMax(100);
            manualOpacityHost.addView(startSeek, matchWrap());
            endValue = text("", 12, TEXT, true);
            endValue.setPadding(0, dp(5), 0, 0);
            manualOpacityHost.addView(endValue);
            endSeek = new SeekBar(MainActivity.this);
            endSeek.setMax(100);
            manualOpacityHost.addView(endSeek, matchWrap());
            root.addView(manualOpacityHost, matchWrap());

            visibleSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int visible = progress + 1;
                    visibleValue.setText("AOD visible time: " + visible + " sec");
                    delaySeek.setMax(Math.max(0, visible - 1));
                    if (delaySeek.getProgress() > visible - 1) delaySeek.setProgress(visible - 1);
                    delayValue.setText("Dimming start delay: " + (delaySeek.getProgress() + 1) + " sec");
                }
                @Override public void onStopTrackingTouch(SeekBar seekBar) { save(); }
            });

            delaySeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    delayValue.setText("Dimming start delay: " + (progress + 1) + " sec");
                }
                @Override public void onStopTrackingTouch(SeekBar seekBar) { save(); }
            });

            opacityMode.setOnCheckedChangeListener((group, checkedId) -> {
                if (suppress) return;
                manualOpacityHost.setVisibility(checkedId == manualRadio.getId() ? View.VISIBLE : View.GONE);
                save();
            });

            startSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean f) { startValue.setText("Start opacity: " + p + "%"); }
                @Override public void onStopTrackingTouch(SeekBar s) { save(); }
            });
            endSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean f) { endValue.setText("End opacity: " + p + "%"); }
                @Override public void onStopTrackingTouch(SeekBar s) { save(); }
            });
            load();
        }

        void load() {
            suppress = true;
            DimmingProfile p = AppPrefs.getProfile(MainActivity.this, prefix);
            visibleSeek.setProgress(p.visibleSeconds - 1);
            delaySeek.setMax(Math.max(0, p.visibleSeconds - 1));
            delaySeek.setProgress(Math.max(0, Math.min(p.visibleSeconds - 1, p.delaySeconds - 1)));
            visibleValue.setText("AOD visible time: " + p.visibleSeconds + " sec");
            delayValue.setText("Dimming start delay: " + p.delaySeconds + " sec");
            autoRadio.setChecked(p.autoOpacity);
            manualRadio.setChecked(!p.autoOpacity);
            startSeek.setProgress(rawToPercent(p.startOpacity));
            endSeek.setProgress(rawToPercent(p.endOpacity));
            startValue.setText("Start opacity: " + rawToPercent(p.startOpacity) + "%");
            endValue.setText("End opacity: " + rawToPercent(p.endOpacity) + "%");
            manualOpacityHost.setVisibility(p.autoOpacity ? View.GONE : View.VISIBLE);
            suppress = false;
        }

        void save() {
            if (suppress) return;
            int visible = visibleSeek.getProgress() + 1;
            int delay = Math.min(visible, delaySeek.getProgress() + 1);
            boolean auto = autoRadio.isChecked();
            DimmingProfile profile = new DimmingProfile(
                    visible, delay, auto,
                    percentToRaw(startSeek.getProgress()),
                    percentToRaw(endSeek.getProgress()));
            AppPrefs.saveProfile(MainActivity.this, prefix, profile);
            refreshSmartService();
        }
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
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
        b.setPadding(dp(14), dp(9), dp(14), dp(9));
        return b;
    }

    private LinearLayout.LayoutParams cardParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void addGap(LinearLayout parent, int dp) {
        View gap = new View(this);
        parent.addView(gap, new LinearLayout.LayoutParams(1, dp(dp)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private static int rawToPercent(int raw) {
        return Math.round(DimmingProfile.clamp(raw, 0, 255) * 100f / 255f);
    }

    private static int percentToRaw(int percent) {
        return Math.round(DimmingProfile.clamp(percent, 0, 100) * 255f / 100f);
    }
}
