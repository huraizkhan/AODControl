package com.huraiz.aodcontrol;

import android.content.Context;

import java.util.Set;

/**
 * Pushes the current AODControl configuration into the persistent Shizuku
 * UserService. In the default mode this replaces Android foreground services,
 * so gesture/automation monitoring does not require a permanent notification.
 */
public final class ShizukuBackgroundEngine {
    private static volatile String lastStatus = "Waiting for Shizuku";

    private ShizukuBackgroundEngine() {}

    public static boolean isEnabled(Context context) {
        return !AppPrefs.isForegroundFallbackEnabled(context);
    }

    public static void sync(Context context) {
        if (context == null || !isEnabled(context)) return;
        IAodShellService shell = ShizukuBridge.getService();
        if (!ShizukuBridge.isReady() || shell == null) {
            lastStatus = "Waiting for Shizuku";
            return;
        }
        try {
            int[] actions = new int[] {
                    AppPrefs.getGestureAction(context, AppPrefs.GESTURE_DOUBLE_TAP),
                    AppPrefs.getGestureAction(context, AppPrefs.GESTURE_TRIPLE_TAP),
                    AppPrefs.getGestureAction(context, AppPrefs.GESTURE_SWIPE_LEFT_TO_RIGHT),
                    AppPrefs.getGestureAction(context, AppPrefs.GESTURE_SWIPE_RIGHT_TO_LEFT),
                    AppPrefs.getGestureAction(context, AppPrefs.GESTURE_SWIPE_UP),
                    AppPrefs.getGestureAction(context, AppPrefs.GESTURE_SWIPE_DOWN),
                    AppPrefs.getGestureAction(context, AppPrefs.GESTURE_LEFT_EDGE_UP),
                    AppPrefs.getGestureAction(context, AppPrefs.GESTURE_LEFT_EDGE_DOWN),
                    AppPrefs.getGestureAction(context, AppPrefs.GESTURE_RIGHT_EDGE_UP),
                    AppPrefs.getGestureAction(context, AppPrefs.GESTURE_RIGHT_EDGE_DOWN),
                    // Extra configuration slot. The AIDL method is unchanged; the stable
                    // v1.4.7 gesture action slots remain indices 0..9.
                    AppPrefs.isPocketProtectionEnabled(context) ? 1 : 0
            };
            shell.configureGestureEngine(
                    AppPrefs.isGesturesEnabled(context) && AppPrefs.anyGestureActionConfigured(context),
                    AppPrefs.getGestureScope(context),
                    AppPrefs.getGestureActiveHeightPercent(context),
                    AppPrefs.getGestureEdgeWidthPercent(context),
                    AppPrefs.getGestureSensitivityPercent(context),
                    actions);

            Set<String> nav = AppPrefs.getNavigationPackages(context);
            shell.configureAutomationEngine(
                    AppPrefs.anyAutomationEnabled(context),
                    AppPrefs.getDefaultBehavior(context), AppPrefs.getDefaultOpacity(context),
                    AppPrefs.isModeEnabled(context, AppPrefs.MODE_CHARGING),
                    AppPrefs.getModeBehavior(context, AppPrefs.MODE_CHARGING),
                    AppPrefs.getModeOpacity(context, AppPrefs.MODE_CHARGING),
                    AppPrefs.isModeEnabled(context, AppPrefs.MODE_NAVIGATION),
                    AppPrefs.getModeBehavior(context, AppPrefs.MODE_NAVIGATION),
                    AppPrefs.getModeOpacity(context, AppPrefs.MODE_NAVIGATION),
                    nav.toArray(new String[0]),
                    AppPrefs.isModeEnabled(context, AppPrefs.MODE_OUTDOOR),
                    AppPrefs.getModeBehavior(context, AppPrefs.MODE_OUTDOOR),
                    AppPrefs.getModeOpacity(context, AppPrefs.MODE_OUTDOOR),
                    AppPrefs.getStartMinutes(context, AppPrefs.MODE_OUTDOOR),
                    AppPrefs.getEndMinutes(context, AppPrefs.MODE_OUTDOOR),
                    AppPrefs.isModeEnabled(context, AppPrefs.MODE_NIGHT),
                    AppPrefs.getModeBehavior(context, AppPrefs.MODE_NIGHT),
                    AppPrefs.getModeOpacity(context, AppPrefs.MODE_NIGHT),
                    AppPrefs.getStartMinutes(context, AppPrefs.MODE_NIGHT),
                    AppPrefs.getEndMinutes(context, AppPrefs.MODE_NIGHT));
            String status = shell.getBackgroundEngineStatus();
            lastStatus = status == null || status.trim().isEmpty() ? "Shizuku background active" : status;
        } catch (Throwable t) {
            lastStatus = "Shizuku background reconnecting";
        }
    }

    public static void stop() {
        IAodShellService shell = ShizukuBridge.getService();
        if (shell == null) return;
        try { shell.stopBackgroundEngine(); } catch (Throwable ignored) {}
    }

    public static String status(Context context) {
        if (AppPrefs.isForegroundFallbackEnabled(context)) return "Foreground fallback";
        if (!ShizukuBridge.isReady()) return "Waiting for Shizuku";
        IAodShellService shell = ShizukuBridge.getService();
        if (shell != null) {
            try {
                String value = shell.getBackgroundEngineStatus();
                if (value != null && !value.trim().isEmpty()) lastStatus = value;
            } catch (Throwable ignored) {}
        }
        return lastStatus;
    }
}
