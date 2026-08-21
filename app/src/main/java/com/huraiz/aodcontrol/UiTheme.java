package com.huraiz.aodcontrol;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;

public final class UiTheme {
    private UiTheme() {}

    public static final class Palette {
        public final boolean dark;
        public final int bg;
        public final int surface;
        public final int surfaceAlt;
        public final int text;
        public final int muted;
        public final int accent;
        public final int onAccent;
        public final int good;
        public final int warning;
        public final int divider;

        Palette(boolean dark, int bg, int surface, int surfaceAlt, int text, int muted,
                int accent, int onAccent, int good, int warning, int divider) {
            this.dark = dark;
            this.bg = bg;
            this.surface = surface;
            this.surfaceAlt = surfaceAlt;
            this.text = text;
            this.muted = muted;
            this.accent = accent;
            this.onAccent = onAccent;
            this.good = good;
            this.warning = warning;
            this.divider = divider;
        }
    }

    public static void applyActivityTheme(Activity activity) {
        activity.setTheme(isDark(activity) ? R.style.AppThemeDark : R.style.AppThemeLight);
    }

    public static Palette palette(Context context) {
        boolean dark = isDark(context);
        boolean dynamic = AppPrefs.isDynamicColorEnabled(context) && Build.VERSION.SDK_INT >= 31;
        boolean pureBlack = dark && AppPrefs.isPureBlackThemeEnabled(context);

        if (dynamic) {
            if (dark) {
                int bg = pureBlack ? Color.BLACK : systemColor(context, "system_neutral1_1000", Color.rgb(12, 12, 12));
                return new Palette(
                        true,
                        bg,
                        systemColor(context, "system_neutral1_900", Color.rgb(28, 28, 28)),
                        systemColor(context, "system_neutral1_800", Color.rgb(42, 42, 42)),
                        systemColor(context, "system_neutral1_50", Color.rgb(245, 245, 245)),
                        systemColor(context, "system_neutral2_300", Color.rgb(185, 185, 185)),
                        systemColor(context, "system_accent1_200", Color.rgb(205, 214, 138)),
                        systemColor(context, "system_accent1_900", Color.rgb(28, 33, 10)),
                        systemColor(context, "system_accent2_200", Color.rgb(149, 213, 178)),
                        systemColor(context, "system_accent3_200", Color.rgb(224, 184, 110)),
                        systemColor(context, "system_neutral1_700", Color.rgb(55, 55, 55))
                );
            }
            return new Palette(
                    false,
                    systemColor(context, "system_neutral1_10", Color.rgb(250, 250, 250)),
                    systemColor(context, "system_neutral1_50", Color.WHITE),
                    systemColor(context, "system_neutral1_100", Color.rgb(239, 239, 239)),
                    systemColor(context, "system_neutral1_900", Color.rgb(30, 30, 30)),
                    systemColor(context, "system_neutral2_600", Color.rgb(95, 95, 95)),
                    systemColor(context, "system_accent1_600", Color.rgb(98, 107, 47)),
                    Color.WHITE,
                    Color.rgb(46, 125, 82),
                    Color.rgb(154, 102, 0),
                    systemColor(context, "system_neutral1_200", Color.rgb(218, 218, 218))
            );
        }

        if (dark) {
            return new Palette(
                    true,
                    pureBlack ? Color.BLACK : Color.rgb(11, 13, 8),
                    pureBlack ? Color.rgb(9, 10, 7) : Color.rgb(18, 21, 14),
                    Color.rgb(27, 31, 22),
                    Color.rgb(241, 242, 234),
                    Color.rgb(167, 170, 155),
                    Color.rgb(205, 214, 138),
                    Color.rgb(28, 33, 10),
                    Color.rgb(149, 213, 178),
                    Color.rgb(224, 184, 110),
                    Color.rgb(43, 48, 37)
            );
        }

        return new Palette(
                false,
                Color.rgb(246, 247, 238),
                Color.WHITE,
                Color.rgb(232, 235, 217),
                Color.rgb(27, 29, 22),
                Color.rgb(102, 106, 93),
                Color.rgb(111, 122, 52),
                Color.WHITE,
                Color.rgb(46, 125, 82),
                Color.rgb(154, 102, 0),
                Color.rgb(214, 217, 200)
        );
    }

    public static boolean isDark(Context context) {
        int appearance = AppPrefs.getAppearance(context);
        if (appearance == AppPrefs.APPEARANCE_LIGHT) return false;
        if (appearance == AppPrefs.APPEARANCE_DARK) return true;
        int mask = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    public static String signature(Context context) {
        return AppPrefs.getAppearance(context) + ":" + AppPrefs.isDynamicColorEnabled(context) + ":"
                + AppPrefs.isPureBlackThemeEnabled(context) + ":" + isDark(context);
    }

    public static void applyWindow(Activity activity, Palette palette) {
        Window window = activity.getWindow();
        window.setStatusBarColor(palette.bg);
        window.setNavigationBarColor(palette.bg);
        int flags = 0;
        if (!palette.dark) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private static int systemColor(Context context, String name, int fallback) {
        int id = context.getResources().getIdentifier(name, "color", "android");
        if (id == 0) return fallback;
        try { return context.getColor(id); }
        catch (Throwable ignored) { return fallback; }
    }

    public static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color), Color.green(color), Color.blue(color));
    }
}
