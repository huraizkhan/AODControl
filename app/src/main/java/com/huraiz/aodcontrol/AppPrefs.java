package com.huraiz.aodcontrol;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppPrefs {
    public static final int MODE_NONE = 0;
    public static final int MODE_ALWAYS_FINGERPRINT = 1;
    public static final int MODE_MANUAL = 2;
    public static final int MODE_SMART = 3;

    public static final String PROFILE_SHARED = "shared";
    public static final String PROFILE_SCREEN = "screen";
    public static final String PROFILE_FINGERPRINT = "fingerprint";

    private static final String PREFS = "aod_control_prefs";
    private static final String KEY_MODE = "mode";
    private static final String KEY_MANUAL_OPACITY = "manual_opacity";
    private static final String KEY_SAME_SETTINGS = "same_trigger_settings";
    private static final String KEY_CALIBRATED = "fingerprint_calibrated";
    private static final String KEY_FP_X = "fingerprint_x";
    private static final String KEY_FP_Y = "fingerprint_y";
    private static final String KEY_FP_RX = "fingerprint_rx";
    private static final String KEY_FP_RY = "fingerprint_ry";

    private AppPrefs() {}

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int getMode(Context context) {
        return prefs(context).getInt(KEY_MODE, MODE_NONE);
    }

    public static void setMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_MODE, mode).apply();
    }

    public static int getManualOpacity(Context context) {
        return DimmingProfile.clamp(prefs(context).getInt(KEY_MANUAL_OPACITY, 255), 0, 255);
    }

    public static void setManualOpacity(Context context, int raw) {
        prefs(context).edit().putInt(KEY_MANUAL_OPACITY, DimmingProfile.clamp(raw, 0, 255)).apply();
    }

    public static boolean useSameTriggerSettings(Context context) {
        return prefs(context).getBoolean(KEY_SAME_SETTINGS, true);
    }

    public static void setUseSameTriggerSettings(Context context, boolean same) {
        prefs(context).edit().putBoolean(KEY_SAME_SETTINGS, same).apply();
    }

    public static DimmingProfile getProfile(Context context, String prefix) {
        SharedPreferences p = prefs(context);
        int visible = p.getInt(prefix + "_visible", 20);
        int delay = p.getInt(prefix + "_delay", 10);
        boolean auto = p.getBoolean(prefix + "_auto", true);
        int start = p.getInt(prefix + "_start", 0);
        int end = p.getInt(prefix + "_end", 255);
        return new DimmingProfile(visible, delay, auto, start, end);
    }

    public static void saveProfile(Context context, String prefix, DimmingProfile profile) {
        profile = profile.copy();
        prefs(context).edit()
                .putInt(prefix + "_visible", profile.visibleSeconds)
                .putInt(prefix + "_delay", profile.delaySeconds)
                .putBoolean(prefix + "_auto", profile.autoOpacity)
                .putInt(prefix + "_start", profile.startOpacity)
                .putInt(prefix + "_end", profile.endOpacity)
                .apply();
    }

    public static DimmingProfile profileForTrigger(Context context, boolean fingerprint) {
        if (useSameTriggerSettings(context)) {
            return getProfile(context, PROFILE_SHARED);
        }
        return getProfile(context, fingerprint ? PROFILE_FINGERPRINT : PROFILE_SCREEN);
    }

    public static boolean isFingerprintCalibrated(Context context) {
        return prefs(context).getBoolean(KEY_CALIBRATED, false);
    }

    public static void saveFingerprintCalibration(Context context, float x, float y, float rx, float ry) {
        prefs(context).edit()
                .putBoolean(KEY_CALIBRATED, true)
                .putFloat(KEY_FP_X, clamp01(x))
                .putFloat(KEY_FP_Y, clamp01(y))
                .putFloat(KEY_FP_RX, Math.max(0.01f, Math.min(0.25f, rx)))
                .putFloat(KEY_FP_RY, Math.max(0.01f, Math.min(0.25f, ry)))
                .apply();
    }

    public static float fingerprintX(Context context) { return prefs(context).getFloat(KEY_FP_X, 0.5f); }
    public static float fingerprintY(Context context) { return prefs(context).getFloat(KEY_FP_Y, 0.82f); }
    public static float fingerprintRadiusX(Context context) { return prefs(context).getFloat(KEY_FP_RX, 0.08f); }
    public static float fingerprintRadiusY(Context context) { return prefs(context).getFloat(KEY_FP_RY, 0.04f); }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
}
