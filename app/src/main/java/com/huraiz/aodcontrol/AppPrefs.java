package com.huraiz.aodcontrol;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class AppPrefs {
    public static final int APPEARANCE_SYSTEM = 0;
    public static final int APPEARANCE_LIGHT = 1;
    public static final int APPEARANCE_DARK = 2;

    public static final int BEHAVIOR_SYSTEM = 0;
    public static final int BEHAVIOR_AOFP = 1;
    public static final int BEHAVIOR_MANUAL = 2;

    public static final String MODE_NIGHT = "night";
    public static final String MODE_NAVIGATION = "navigation";
    public static final String MODE_OUTDOOR = "outdoor";
    public static final String MODE_CHARGING = "charging";

    private static final String PREFS = "aod_control_prefs";
    private static final String KEY_DEFAULT_BEHAVIOR = "default_behavior";
    private static final String KEY_DEFAULT_OPACITY = "default_opacity";
    private static final String KEY_NAV_PACKAGES = "navigation_packages";
    private static final String KEY_LAST_REASON = "last_reason";
    private static final String KEY_LAST_STATE = "last_state";
    private static final String KEY_LAST_OK = "last_ok";
    private static final String KEY_APPEARANCE = "appearance";
    private static final String KEY_DYNAMIC_COLOR = "dynamic_color";
    private static final String KEY_PURE_BLACK = "pure_black_theme";

    private static final String KEY_ORIGINAL_CAPTURED = "original_captured";
    private static final String KEY_ORIGINAL_DOZE = "original_doze";
    private static final String KEY_ORIGINAL_UDFPS = "original_udfps";
    private static final String KEY_ORIGINAL_DIMMING = "original_dimming";
    private static final String NULL = "__AODCONTROL_NULL__";

    private AppPrefs() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }


    public static int getAppearance(Context context) {
        int value = prefs(context).getInt(KEY_APPEARANCE, APPEARANCE_DARK);
        if (value == APPEARANCE_LIGHT || value == APPEARANCE_SYSTEM) return value;
        return APPEARANCE_DARK;
    }

    public static void setAppearance(Context context, int appearance) {
        if (appearance != APPEARANCE_SYSTEM && appearance != APPEARANCE_LIGHT) appearance = APPEARANCE_DARK;
        prefs(context).edit().putInt(KEY_APPEARANCE, appearance).apply();
    }

    public static boolean isDynamicColorEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DYNAMIC_COLOR, false);
    }

    public static void setDynamicColorEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply();
    }

    public static boolean isPureBlackThemeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_PURE_BLACK, false);
    }

    public static void setPureBlackThemeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PURE_BLACK, enabled).apply();
    }

    public static int getDefaultBehavior(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.contains(KEY_DEFAULT_BEHAVIOR) && p.contains("mode")) {
            int oldMode = p.getInt("mode", 0);
            int migrated = oldMode == 2 ? BEHAVIOR_MANUAL
                    : (oldMode == 1 || oldMode == 3 ? BEHAVIOR_AOFP : BEHAVIOR_SYSTEM);
            p.edit().putInt(KEY_DEFAULT_BEHAVIOR, migrated).apply();
            return migrated;
        }
        return sanitizeBehavior(p.getInt(KEY_DEFAULT_BEHAVIOR, BEHAVIOR_SYSTEM));
    }

    public static void setDefaultBehavior(Context context, int behavior) {
        prefs(context).edit().putInt(KEY_DEFAULT_BEHAVIOR, sanitizeBehavior(behavior)).apply();
    }

    public static int getDefaultOpacity(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.contains(KEY_DEFAULT_OPACITY) && p.contains("manual_opacity")) {
            int migrated = clamp(p.getInt("manual_opacity", 255), 0, 255);
            p.edit().putInt(KEY_DEFAULT_OPACITY, migrated).apply();
            return migrated;
        }
        return clamp(p.getInt(KEY_DEFAULT_OPACITY, 255), 0, 255);
    }

    public static void setDefaultOpacity(Context context, int raw) {
        prefs(context).edit().putInt(KEY_DEFAULT_OPACITY, clamp(raw, 0, 255)).apply();
    }

    public static boolean isModeEnabled(Context context, String mode) {
        return prefs(context).getBoolean(mode + "_enabled", false);
    }

    public static void setModeEnabled(Context context, String mode, boolean enabled) {
        prefs(context).edit().putBoolean(mode + "_enabled", enabled).apply();
    }

    public static int getModeBehavior(Context context, String mode) {
        return sanitizeBehavior(prefs(context).getInt(mode + "_behavior", BEHAVIOR_SYSTEM));
    }

    public static void setModeBehavior(Context context, String mode, int behavior) {
        prefs(context).edit().putInt(mode + "_behavior", sanitizeBehavior(behavior)).apply();
    }

    public static int getModeOpacity(Context context, String mode) {
        return clamp(prefs(context).getInt(mode + "_opacity", 255), 0, 255);
    }

    public static void setModeOpacity(Context context, String mode, int raw) {
        prefs(context).edit().putInt(mode + "_opacity", clamp(raw, 0, 255)).apply();
    }

    public static int getStartMinutes(Context context, String mode) {
        int fallback = MODE_NIGHT.equals(mode) ? 19 * 60 : 8 * 60;
        return clamp(prefs(context).getInt(mode + "_start_minutes", fallback), 0, 1439);
    }

    public static int getEndMinutes(Context context, String mode) {
        int fallback = MODE_NIGHT.equals(mode) ? 6 * 60 : 18 * 60;
        return clamp(prefs(context).getInt(mode + "_end_minutes", fallback), 0, 1439);
    }

    public static void setTimeRange(Context context, String mode, int startMinutes, int endMinutes) {
        prefs(context).edit()
                .putInt(mode + "_start_minutes", clamp(startMinutes, 0, 1439))
                .putInt(mode + "_end_minutes", clamp(endMinutes, 0, 1439))
                .apply();
    }

    public static Set<String> getNavigationPackages(Context context) {
        Set<String> stored = prefs(context).getStringSet(KEY_NAV_PACKAGES, Collections.emptySet());
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }

    public static void setNavigationPackages(Context context, Set<String> packages) {
        prefs(context).edit().putStringSet(KEY_NAV_PACKAGES,
                packages == null ? Collections.emptySet() : new HashSet<>(packages)).apply();
    }


    public static void disableAllModes(Context context) {
        prefs(context).edit()
                .putBoolean(MODE_NIGHT + "_enabled", false)
                .putBoolean(MODE_NAVIGATION + "_enabled", false)
                .putBoolean(MODE_OUTDOOR + "_enabled", false)
                .putBoolean(MODE_CHARGING + "_enabled", false)
                .apply();
    }

    public static boolean anyAutomationEnabled(Context context) {
        return isModeEnabled(context, MODE_NIGHT)
                || isModeEnabled(context, MODE_NAVIGATION)
                || isModeEnabled(context, MODE_OUTDOOR)
                || isModeEnabled(context, MODE_CHARGING);
    }

    public static Behavior getDefaultBehaviorConfig(Context context) {
        return new Behavior(getDefaultBehavior(context), getDefaultOpacity(context));
    }

    public static Behavior getModeBehaviorConfig(Context context, String mode) {
        return new Behavior(getModeBehavior(context, mode), getModeOpacity(context, mode));
    }

    public static void saveLastState(Context context, String reason, Behavior behavior, boolean ok) {
        prefs(context).edit()
                .putString(KEY_LAST_REASON, reason == null ? "Default" : reason)
                .putString(KEY_LAST_STATE, describeBehavior(behavior))
                .putBoolean(KEY_LAST_OK, ok)
                .apply();
    }

    public static String getLastReason(Context context) {
        return prefs(context).getString(KEY_LAST_REASON, "Default");
    }

    public static String getLastState(Context context) {
        return prefs(context).getString(KEY_LAST_STATE, "System default");
    }

    public static boolean getLastOk(Context context) {
        return prefs(context).getBoolean(KEY_LAST_OK, true);
    }

    public static boolean hasOriginalBaseline(Context context) {
        return prefs(context).getBoolean(KEY_ORIGINAL_CAPTURED, false);
    }

    public static synchronized void saveOriginalBaseline(Context context, String doze, String udfps, String dimming) {
        if (hasOriginalBaseline(context)) return;
        prefs(context).edit()
                .putBoolean(KEY_ORIGINAL_CAPTURED, true)
                .putString(KEY_ORIGINAL_DOZE, encodeNullable(doze))
                .putString(KEY_ORIGINAL_UDFPS, encodeNullable(udfps))
                .putString(KEY_ORIGINAL_DIMMING, encodeNullable(dimming))
                .commit();
    }

    public static String getOriginalDoze(Context context) {
        return decodeNullable(prefs(context).getString(KEY_ORIGINAL_DOZE, NULL));
    }

    public static String getOriginalUdfps(Context context) {
        return decodeNullable(prefs(context).getString(KEY_ORIGINAL_UDFPS, NULL));
    }

    public static String getOriginalDimming(Context context) {
        return decodeNullable(prefs(context).getString(KEY_ORIGINAL_DIMMING, NULL));
    }

    public static void clearOriginalBaseline(Context context) {
        prefs(context).edit()
                .remove(KEY_ORIGINAL_CAPTURED)
                .remove(KEY_ORIGINAL_DOZE)
                .remove(KEY_ORIGINAL_UDFPS)
                .remove(KEY_ORIGINAL_DIMMING)
                .apply();
    }

    public static String describeBehavior(Behavior behavior) {
        if (behavior == null || behavior.type == BEHAVIOR_SYSTEM) return "System default";
        if (behavior.type == BEHAVIOR_AOFP) return "AOFP • AOD blank";
        return "Manual opacity • " + Math.round(behavior.opacity * 100f / 255f) + "%";
    }

    private static int sanitizeBehavior(int value) {
        if (value == BEHAVIOR_AOFP || value == BEHAVIOR_MANUAL) return value;
        return BEHAVIOR_SYSTEM;
    }

    private static String encodeNullable(String value) {
        return value == null ? NULL : value;
    }

    private static String decodeNullable(String value) {
        return value == null || NULL.equals(value) ? null : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class Behavior {
        public final int type;
        public final int opacity;

        public Behavior(int type, int opacity) {
            this.type = sanitizeBehavior(type);
            this.opacity = clamp(opacity, 0, 255);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Behavior)) return false;
            Behavior b = (Behavior) other;
            return type == b.type && opacity == b.opacity;
        }

        @Override
        public int hashCode() {
            return 31 * type + opacity;
        }
    }
}
