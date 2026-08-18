package com.huraiz.aodcontrol;

import java.util.ArrayList;
import java.util.List;

public final class AodSettings {
    public static final String AOD_CONSTANTS = "always_on_display_constants";
    public static final String DIMMING_KEY = "dimming_scrim_array";
    public static final String DOZE_ALWAYS_ON = "doze_always_on";
    public static final String SCREEN_OFF_UDFPS_ENABLED = "screen_off_udfps_enabled";
    public static final int DEFAULT_BUCKET_COUNT = 8;

    private AodSettings() {}

    public static String setKey(String constants, String key, String value) {
        List<String> out = new ArrayList<>();
        boolean replaced = false;
        if (constants != null && !constants.trim().isEmpty() && !"null".equals(constants.trim())) {
            for (String item : constants.split(",")) {
                String trimmed = item.trim();
                if (trimmed.isEmpty()) continue;
                int eq = trimmed.indexOf('=');
                if (eq > 0 && trimmed.substring(0, eq).trim().equals(key)) {
                    if (!replaced) {
                        out.add(key + "=" + value);
                        replaced = true;
                    }
                } else {
                    out.add(trimmed);
                }
            }
        }
        if (!replaced) out.add(key + "=" + value);
        return join(out);
    }

    public static String removeKey(String constants, String key) {
        if (constants == null || constants.trim().isEmpty() || "null".equals(constants.trim())) return "";
        List<String> out = new ArrayList<>();
        for (String item : constants.split(",")) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            if (eq > 0 && trimmed.substring(0, eq).trim().equals(key)) continue;
            out.add(trimmed);
        }
        return join(out);
    }

    public static String getValue(String constants, String key) {
        if (constants == null || constants.trim().isEmpty() || "null".equals(constants.trim())) return null;
        for (String item : constants.split(",")) {
            String trimmed = item.trim();
            int eq = trimmed.indexOf('=');
            if (eq > 0 && trimmed.substring(0, eq).trim().equals(key)) {
                return trimmed.substring(eq + 1).trim();
            }
        }
        return null;
    }

    public static int bucketCountFromConstants(String constants) {
        String value = getValue(constants, DIMMING_KEY);
        if (value == null || value.isEmpty()) return 0;
        String[] parts = value.split(":");
        return parts.length >= 2 && parts.length <= 32 ? parts.length : 0;
    }

    public static String uniformArrayString(int raw, int count) {
        raw = clamp(raw, 0, 255);
        count = Math.max(2, Math.min(32, count));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) b.append(':');
            b.append(raw);
        }
        return b.toString();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String join(List<String> items) {
        StringBuilder b = new StringBuilder();
        for (String item : items) {
            if (b.length() > 0) b.append(',');
            b.append(item);
        }
        return b.toString();
    }
}
