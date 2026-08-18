package com.huraiz.aodcontrol;

import java.util.ArrayList;
import java.util.List;

public final class AodSettings {
    public static final String AOD_CONSTANTS = "always_on_display_constants";
    public static final String DIMMING_KEY = "dimming_scrim_array";
    public static final String DOZE_ALWAYS_ON = "doze_always_on";
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

    public static String uniformArrayString(int raw, int count) {
        raw = DimmingProfile.clamp(raw, 0, 255);
        count = Math.max(2, Math.min(32, count));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) b.append(':');
            b.append(raw);
        }
        return b.toString();
    }

    public static String arrayString(int[] values) {
        if (values == null || values.length == 0) return uniformArrayString(0, DEFAULT_BUCKET_COUNT);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) b.append(':');
            b.append(DimmingProfile.clamp(values[i], -1, 255));
        }
        return b.toString();
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
