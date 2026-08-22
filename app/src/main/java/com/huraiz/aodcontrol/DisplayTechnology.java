package com.huraiz.aodcontrol;

import java.util.Locale;

public final class DisplayTechnology {
    private DisplayTechnology() {}

    public static int detectFromHints(String hints) {
        if (hints == null || hints.trim().isEmpty()) return AppPrefs.DISPLAY_UNKNOWN;
        String value = hints.toLowerCase(Locale.US);

        // Strong OLED terms first. "led" alone is intentionally ignored because LCDs use LED backlights.
        if (value.contains("amoled") || value.contains("poled") || value.contains("p-oled")
                || value.contains("oled") || value.contains("organic light")) {
            return AppPrefs.DISPLAY_OLED;
        }
        if (value.contains("ips lcd") || value.contains("tft lcd") || value.contains("ltps lcd")
                || value.contains("lcd panel") || value.contains("panel_lcd") || value.contains("panel=lcd")) {
            return AppPrefs.DISPLAY_LCD;
        }
        return AppPrefs.DISPLAY_UNKNOWN;
    }
}
