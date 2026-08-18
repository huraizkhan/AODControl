package com.huraiz.aodcontrol;

import android.content.Context;

public final class AodApplier {
    private AodApplier() {}

    public static Result apply(Context context, AppPrefs.Behavior behavior) {
        IAodShellService shell = ShizukuBridge.getService();
        if (shell == null) return new Result(false, "Shizuku service unavailable");

        try {
            captureBaselineIfNeeded(context, shell);

            if (behavior == null || behavior.type == AppPrefs.BEHAVIOR_SYSTEM) {
                String error = restoreSystemBehavior(context, shell);
                return new Result(isEmpty(error), error);
            }

            String error = shell.putSetting("secure", AodSettings.DOZE_ALWAYS_ON, "1");
            if (!isEmpty(error)) return new Result(false, error);

            if (behavior.type == AppPrefs.BEHAVIOR_AOFP) {
                // Best effort: devices that do not implement this AOSP/Pixel key simply ignore it.
                shell.putSetting("secure", AodSettings.SCREEN_OFF_UDFPS_ENABLED, "1");
                error = shell.setUniformDimming(255, 0);
                return new Result(isEmpty(error), error);
            }

            // Manual opacity should not silently leave AOFP forced from a previous AOFP state.
            restoreSetting(shell, "secure", AodSettings.SCREEN_OFF_UDFPS_ENABLED,
                    AppPrefs.getOriginalUdfps(context));
            error = shell.setUniformDimming(behavior.opacity, 0);
            return new Result(isEmpty(error), error);
        } catch (Throwable t) {
            return new Result(false, t.getClass().getSimpleName() + safeMessage(t));
        }
    }

    public static Result restoreAndForgetBaseline(Context context) {
        IAodShellService shell = ShizukuBridge.getService();
        if (shell == null) return new Result(false, "Shizuku service unavailable");
        try {
            if (!AppPrefs.hasOriginalBaseline(context)) {
                String error = shell.removeDimmingOverride();
                return new Result(isEmpty(error), error);
            }
            String error = restoreSystemBehavior(context, shell);
            if (isEmpty(error)) AppPrefs.clearOriginalBaseline(context);
            return new Result(isEmpty(error), error);
        } catch (Throwable t) {
            return new Result(false, t.getClass().getSimpleName() + safeMessage(t));
        }
    }

    private static void captureBaselineIfNeeded(Context context, IAodShellService shell) throws Exception {
        if (AppPrefs.hasOriginalBaseline(context)) return;
        String doze = shell.getSetting("secure", AodSettings.DOZE_ALWAYS_ON);
        String udfps = shell.getSetting("secure", AodSettings.SCREEN_OFF_UDFPS_ENABLED);
        String constants = shell.getSetting("global", AodSettings.AOD_CONSTANTS);
        String dimming = AodSettings.getValue(constants, AodSettings.DIMMING_KEY);
        AppPrefs.saveOriginalBaseline(context, doze, udfps, dimming);
    }

    private static String restoreSystemBehavior(Context context, IAodShellService shell) throws Exception {
        if (!AppPrefs.hasOriginalBaseline(context)) {
            return shell.removeDimmingOverride();
        }

        String error = restoreSetting(shell, "secure", AodSettings.DOZE_ALWAYS_ON,
                AppPrefs.getOriginalDoze(context));
        if (!isEmpty(error)) return error;

        error = restoreSetting(shell, "secure", AodSettings.SCREEN_OFF_UDFPS_ENABLED,
                AppPrefs.getOriginalUdfps(context));
        if (!isEmpty(error)) return error;

        // "System default" means use the device/SystemUI AOD opacity curve, not a
        // dimming override that may have been left by this app or an older build.
        return shell.removeDimmingOverride();
    }

    private static String restoreSetting(IAodShellService shell, String namespace, String key, String value)
            throws Exception {
        return value == null ? shell.deleteSetting(namespace, key) : shell.putSetting(namespace, key, value);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isEmpty() ? "" : ": " + message;
    }

    public static final class Result {
        public final boolean ok;
        public final String error;

        Result(boolean ok, String error) {
            this.ok = ok;
            this.error = error == null ? "" : error;
        }
    }
}
