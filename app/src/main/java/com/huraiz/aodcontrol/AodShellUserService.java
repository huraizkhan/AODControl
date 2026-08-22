package com.huraiz.aodcontrol;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Restricted Shizuku UserService. It exposes only the settings operations used
 * by AODControl; it is not a general-purpose shell command bridge.
 */
public class AodShellUserService extends IAodShellService.Stub {
    private volatile int[] defaultDimmingCache;

    public AodShellUserService() {}

    @Override
    public String getSetting(String namespace, String key) {
        if (!validNamespace(namespace) || !validKey(key)) return null;
        CommandResult result = run("/system/bin/settings", "get", namespace, key);
        if (result.exitCode != 0) return null;
        String value = result.output.trim();
        return "null".equals(value) ? null : value;
    }

    @Override
    public String putSetting(String namespace, String key, String value) {
        if (!validNamespace(namespace) || !validKey(key) || value == null) {
            return "Invalid settings request";
        }
        return errorFrom(run("/system/bin/settings", "put", namespace, key, value));
    }

    @Override
    public String deleteSetting(String namespace, String key) {
        if (!validNamespace(namespace) || !validKey(key)) return "Invalid settings request";
        return errorFrom(run("/system/bin/settings", "delete", namespace, key));
    }

    @Override
    public String setUniformDimming(int raw, int bucketCount) {
        raw = clamp(raw, 0, 255);
        String current = getSetting("global", AodSettings.AOD_CONSTANTS);

        if (bucketCount <= 0) {
            bucketCount = AodSettings.bucketCountFromConstants(current);
        }
        if (bucketCount <= 0) {
            int[] defaults = getSystemDefaultDimmingArray();
            bucketCount = defaults == null ? AodSettings.DEFAULT_BUCKET_COUNT : defaults.length;
        }
        bucketCount = Math.max(2, Math.min(32, bucketCount));

        String value = AodSettings.uniformArrayString(raw, bucketCount);
        String updated = AodSettings.setKey(current, AodSettings.DIMMING_KEY, value);
        return putSetting("global", AodSettings.AOD_CONSTANTS, updated);
    }

    @Override
    public String removeDimmingOverride() {
        String current = getSetting("global", AodSettings.AOD_CONSTANTS);
        String updated = AodSettings.removeKey(current, AodSettings.DIMMING_KEY);
        if (updated == null || updated.isEmpty()) {
            return deleteSetting("global", AodSettings.AOD_CONSTANTS);
        }
        return putSetting("global", AodSettings.AOD_CONSTANTS, updated);
    }

    @Override
    public int[] getSystemDefaultDimmingArray() {
        int[] cached = defaultDimmingCache;
        if (cached != null && cached.length >= 2) return cached.clone();

        CommandResult result = run(
                "/system/bin/cmd", "overlay", "lookup",
                "com.android.systemui",
                "com.android.systemui:array/config_doze_brightness_sensor_to_scrim_opacity");
        int[] parsed = parseResourceIntArray(result.output);
        if (parsed.length >= 2) {
            defaultDimmingCache = parsed.clone();
            return parsed;
        }

        int[] fallback = new int[] {0, 0, 0, 0, 0, 0, 0, 0};
        defaultDimmingCache = fallback.clone();
        return fallback;
    }


    @Override
    public String getForegroundPackage() {
        CommandResult activity = run("/system/bin/dumpsys", "activity", "activities");
        String pkg = parseFocusedPackage(activity.output);
        if (pkg != null) return pkg;

        CommandResult window = run("/system/bin/dumpsys", "window", "windows");
        return parseFocusedPackage(window.output);
    }

    @Override
    public String getDisplayPanelHints() {
        StringBuilder out = new StringBuilder();
        appendRelevantLines(out, run("/system/bin/getprop").output);
        appendRelevantLines(out, run("/system/bin/dumpsys", "display").output);
        if (out.length() > 8000) return out.substring(0, 8000);
        return out.toString();
    }

    @Override
    public String sleepScreen() {
        return errorFrom(run("/system/bin/input", "keyevent", "223"));
    }

    @Override
    public void destroy() {
        System.exit(0);
    }


    private static void appendRelevantLines(StringBuilder out, String text) {
        if (text == null || text.isEmpty()) return;
        for (String line : text.split("\\r?\\n")) {
            String lower = line.toLowerCase();
            if (!(lower.contains("display") || lower.contains("panel") || lower.contains("oled")
                    || lower.contains("amoled") || lower.contains("poled") || lower.contains("lcd")
                    || lower.contains("dsi"))) continue;
            if (out.length() > 0) out.append('\n');
            out.append(line);
            if (out.length() >= 8000) return;
        }
    }

    private static int[] parseResourceIntArray(String output) {
        if (output == null) return new int[0];
        String candidate = output.trim();
        int left = candidate.lastIndexOf('[');
        int right = candidate.indexOf(']', Math.max(0, left));
        if (left >= 0 && right > left) candidate = candidate.substring(left + 1, right);

        List<Integer> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?<![A-Za-z0-9_])-?\\d+(?![A-Za-z0-9_])").matcher(candidate);
        while (matcher.find()) {
            int value = parseIntSafe(matcher.group(), Integer.MIN_VALUE);
            if (value >= -1 && value <= 255) values.add(value);
        }
        if (values.size() < 2 || values.size() > 32) return new int[0];
        int[] out = new int[values.size()];
        for (int i = 0; i < values.size(); i++) out[i] = values.get(i);
        return out;
    }


    private static String parseFocusedPackage(String output) {
        if (output == null || output.isEmpty()) return null;
        Pattern component = Pattern.compile("([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/(?:\\.?[A-Za-z0-9_.$]+)");
        String[] priority = new String[] {
                "topResumedActivity", "mResumedActivity", "mCurrentFocus", "mFocusedApp"
        };
        for (String key : priority) {
            for (String line : output.split("\\r?\\n")) {
                if (!line.contains(key)) continue;
                Matcher matcher = component.matcher(line);
                if (matcher.find()) return matcher.group(1);
            }
        }
        return null;
    }

    private static boolean validNamespace(String namespace) {
        return "global".equals(namespace) || "secure".equals(namespace);
    }

    private static boolean validKey(String key) {
        return AodSettings.AOD_CONSTANTS.equals(key)
                || AodSettings.DOZE_ALWAYS_ON.equals(key)
                || AodSettings.SCREEN_OFF_UDFPS_ENABLED.equals(key);
    }

    private static String errorFrom(CommandResult result) {
        if (result.exitCode == 0) return "";
        String out = result.output.trim();
        return out.isEmpty() ? "Command exited with code " + result.exitCode : out;
    }

    private static CommandResult run(String... command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) output.append('\n');
                    output.append(line);
                }
            }
            int exit = process.waitFor();
            return new CommandResult(exit, output.toString());
        } catch (Throwable t) {
            return new CommandResult(-1, t.getClass().getSimpleName() + ": " + safe(t.getMessage()));
        }
    }

    private static int parseIntSafe(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (Throwable ignored) { return fallback; }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }
}
