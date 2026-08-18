package com.huraiz.aodcontrol;

import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs in a Shizuku UserService process. With non-root Shizuku this process has
 * the ADB shell identity (uid 2000). The exposed API is deliberately limited to
 * the AOD settings and touch monitoring used by this app.
 */
public class AodShellUserService extends IAodShellService.Stub {
    private static final Pattern DEVICE_PATTERN = Pattern.compile("^add device \\d+:\\s+(\\S+)");
    private static final Pattern NAME_PATTERN = Pattern.compile("name:\\s*\"([^\"]+)\"");
    private static final Pattern X_RANGE_PATTERN = Pattern.compile(
            "ABS_MT_POSITION_X.*?min\\s+(-?\\d+),\\s+max\\s+(-?\\d+)");
    private static final Pattern Y_RANGE_PATTERN = Pattern.compile(
            "ABS_MT_POSITION_Y.*?min\\s+(-?\\d+),\\s+max\\s+(-?\\d+)");

    private final Object touchLock = new Object();
    private volatile Process touchProcess;
    private volatile Thread touchThread;
    private volatile boolean stopTouchRequested;
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
        raw = DimmingProfile.clamp(raw, 0, 255);
        if (bucketCount <= 0) {
            int[] defaults = getSystemDefaultDimmingArray();
            bucketCount = defaults == null ? AodSettings.DEFAULT_BUCKET_COUNT : defaults.length;
        }
        bucketCount = Math.max(2, Math.min(32, bucketCount));
        String current = getSetting("global", AodSettings.AOD_CONSTANTS);
        String value = AodSettings.uniformArrayString(raw, bucketCount);
        String updated = AodSettings.setKey(current, AodSettings.DIMMING_KEY, value);
        return putSetting("global", AodSettings.AOD_CONSTANTS, updated);
    }

    @Override
    public String setDimmingArray(int[] values) {
        if (values == null || values.length == 0 || values.length > 32) {
            return "Invalid dimming array";
        }
        String current = getSetting("global", AodSettings.AOD_CONSTANTS);
        String updated = AodSettings.setKey(current, AodSettings.DIMMING_KEY,
                AodSettings.arrayString(values));
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
        CommandResult result = run("/system/bin/cmd", "overlay", "lookup",
                "com.android.systemui",
                "com.android.systemui:array/config_doze_brightness_sensor_to_scrim_opacity");
        int[] parsed = parseResourceIntArray(result.output);
        if (parsed.length >= 2) {
            defaultDimmingCache = parsed.clone();
            return parsed;
        }

        // AOSP-compatible fallback. Extra buckets are harmless and help vendors
        // that expose more ambient brightness buckets than AOSP's default five.
        int[] fallback = new int[] {0, 0, 0, 0, 0, 0, 0, 0};
        defaultDimmingCache = fallback.clone();
        return fallback;
    }

    @Override
    public String getTouchCapabilities() {
        TouchDevice device = findTouchDevice();
        if (device == null) {
            return "Unavailable: no multitouch input device readable by shell";
        }
        return String.format(Locale.US, "Available: %s (%s), X %d..%d, Y %d..%d",
                device.name, device.path, device.minX, device.maxX, device.minY, device.maxY);
    }

    @Override
    public int startTouchMonitor(float centerXNorm, float centerYNorm,
                                 float radiusXNorm, float radiusYNorm,
                                 IFingerprintTouchCallback callback) {
        if (callback == null) return -3;
        centerXNorm = clampFloat(centerXNorm, 0f, 1f);
        centerYNorm = clampFloat(centerYNorm, 0f, 1f);
        radiusXNorm = clampFloat(radiusXNorm, 0.01f, 0.30f);
        radiusYNorm = clampFloat(radiusYNorm, 0.01f, 0.30f);

        final TouchDevice device = findTouchDevice();
        if (device == null) return -1;

        stopTouchMonitor();
        stopTouchRequested = false;
        final float cx = centerXNorm;
        final float cy = centerYNorm;
        final float rx = radiusXNorm;
        final float ry = radiusYNorm;

        synchronized (touchLock) {
            touchThread = new Thread(() -> monitorTouch(device, cx, cy, rx, ry, callback),
                    "AODControl-getevent");
            touchThread.setDaemon(true);
            touchThread.start();
        }
        return 0;
    }

    @Override
    public void stopTouchMonitor() {
        stopTouchRequested = true;
        Process process = touchProcess;
        if (process != null) {
            try { process.destroy(); } catch (Throwable ignored) {}
        }
        Thread thread = touchThread;
        if (thread != null) {
            try { thread.interrupt(); } catch (Throwable ignored) {}
        }
        touchProcess = null;
        touchThread = null;
    }

    @Override
    public void destroy() {
        stopTouchMonitor();
        System.exit(0);
    }

    private void monitorTouch(TouchDevice device, float cx, float cy, float rx, float ry,
                              IFingerprintTouchCallback callback) {
        int currentX = Integer.MIN_VALUE;
        int currentY = Integer.MIN_VALUE;
        boolean touching = false;
        boolean triggeredThisContact = false;

        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "/system/bin/getevent", "-lt", device.path);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            touchProcess = process;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!stopTouchRequested && (line = reader.readLine()) != null) {
                    if (line.contains("ABS_MT_POSITION_X")) {
                        Integer value = parseEventValue(line);
                        if (value != null) {
                            currentX = value;
                            touching = true;
                        }
                    } else if (line.contains("ABS_MT_POSITION_Y")) {
                        Integer value = parseEventValue(line);
                        if (value != null) {
                            currentY = value;
                            touching = true;
                        }
                    } else if (line.contains("BTN_TOUCH")) {
                        Integer value = parseEventValue(line);
                        if (value != null) {
                            touching = value != 0;
                            if (!touching) triggeredThisContact = false;
                        }
                    } else if (line.contains("ABS_MT_TRACKING_ID")) {
                        Integer value = parseEventValue(line);
                        if (value != null) {
                            if (value == -1) {
                                touching = false;
                                triggeredThisContact = false;
                                currentX = Integer.MIN_VALUE;
                                currentY = Integer.MIN_VALUE;
                            } else {
                                touching = true;
                            }
                        }
                    } else if (line.contains("SYN_REPORT")) {
                        if (touching && !triggeredThisContact
                                && currentX != Integer.MIN_VALUE && currentY != Integer.MIN_VALUE) {
                            float nx = normalize(currentX, device.minX, device.maxX);
                            float ny = normalize(currentY, device.minY, device.maxY);
                            float dx = (nx - cx) / rx;
                            float dy = (ny - cy) / ry;
                            if ((dx * dx + dy * dy) <= 1f) {
                                try {
                                    callback.onFingerprintTouch();
                                    triggeredThisContact = true;
                                } catch (RemoteException e) {
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            try { process.waitFor(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        } catch (Throwable t) {
            if (!stopTouchRequested) {
                try {
                    callback.onMonitorError(t.getClass().getSimpleName() + ": " + safe(t.getMessage()));
                } catch (RemoteException ignored) {}
            }
        } finally {
            touchProcess = null;
        }
    }

    private TouchDevice findTouchDevice() {
        CommandResult result = run("/system/bin/getevent", "-pl");
        if (result.exitCode != 0 || result.output.isEmpty()) return null;

        List<TouchDevice> devices = new ArrayList<>();
        TouchDevice current = null;
        for (String rawLine : result.output.split("\\r?\\n")) {
            String line = rawLine.trim();
            Matcher deviceMatcher = DEVICE_PATTERN.matcher(line);
            if (deviceMatcher.find()) {
                if (current != null && current.valid()) devices.add(current);
                current = new TouchDevice();
                current.path = deviceMatcher.group(1);
                continue;
            }
            if (current == null) continue;

            Matcher nameMatcher = NAME_PATTERN.matcher(line);
            if (nameMatcher.find()) current.name = nameMatcher.group(1);

            Matcher xMatcher = X_RANGE_PATTERN.matcher(line);
            if (xMatcher.find()) {
                current.minX = parseIntSafe(xMatcher.group(1), 0);
                current.maxX = parseIntSafe(xMatcher.group(2), -1);
            }
            Matcher yMatcher = Y_RANGE_PATTERN.matcher(line);
            if (yMatcher.find()) {
                current.minY = parseIntSafe(yMatcher.group(1), 0);
                current.maxY = parseIntSafe(yMatcher.group(2), -1);
            }
        }
        if (current != null && current.valid()) devices.add(current);
        if (devices.isEmpty()) return null;

        TouchDevice best = null;
        int bestScore = Integer.MIN_VALUE;
        for (TouchDevice device : devices) {
            String n = device.name == null ? "" : device.name.toLowerCase(Locale.US);
            int score = (device.maxX - device.minX) + (device.maxY - device.minY);
            if (n.contains("touch") || n.contains("fts") || n.contains("goodix")
                    || n.contains("synapt") || n.contains("nvt") || n.contains("ili")
                    || n.contains("sec_ts")) {
                score += 1_000_000;
            }
            if (score > bestScore) {
                best = device;
                bestScore = score;
            }
        }
        return best;
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

    private static Integer parseEventValue(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 0) return null;
        String token = parts[parts.length - 1].trim();
        if ("DOWN".equalsIgnoreCase(token)) return 1;
        if ("UP".equalsIgnoreCase(token)) return 0;
        try {
            if (token.startsWith("0x") || token.startsWith("0X")) {
                return (int) Long.parseLong(token.substring(2), 16);
            }
            if (token.matches("[0-9a-fA-F]{8}")) {
                return (int) Long.parseLong(token, 16);
            }
            return Integer.parseInt(token);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static float normalize(int value, int min, int max) {
        if (max <= min) return 0f;
        return clampFloat((value - min) / (float) (max - min), 0f, 1f);
    }

    private static boolean validNamespace(String namespace) {
        return "global".equals(namespace) || "secure".equals(namespace);
    }

    private static boolean validKey(String key) {
        return AodSettings.AOD_CONSTANTS.equals(key) || AodSettings.DOZE_ALWAYS_ON.equals(key);
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

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static final class CommandResult {
        final int exitCode;
        final String output;
        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }

    private static final class TouchDevice {
        String path;
        String name = "touchscreen";
        int minX = 0;
        int maxX = -1;
        int minY = 0;
        int maxY = -1;

        boolean valid() {
            return path != null && maxX > minX && maxY > minY;
        }
    }
}
