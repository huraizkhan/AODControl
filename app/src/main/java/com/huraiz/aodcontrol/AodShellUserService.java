package com.huraiz.aodcontrol;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Restricted Shizuku UserService. Only AOD settings, foreground-package lookup,
 * wake-screen, and read-only touchscreen observation are exposed.
 */
public class AodShellUserService extends IAodShellService.Stub {
    private volatile int[] defaultDimmingCache;

    private final Object touchLock = new Object();
    private TouchDevice touchDevice;
    private Process touchProcess;
    private BufferedReader touchReader;
    private final TouchAccumulator touchAccumulator = new TouchAccumulator();

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

        if (bucketCount <= 0) bucketCount = AodSettings.bucketCountFromConstants(current);
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
        if (updated == null || updated.isEmpty()) return deleteSetting("global", AodSettings.AOD_CONSTANTS);
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
        return parseFocusedPackage(run("/system/bin/dumpsys", "window", "windows").output);
    }

    @Override
    public String getTouchCapabilities() {
        synchronized (touchLock) {
            TouchDevice device = ensureTouchDeviceLocked();
            if (device == null) return "Unavailable • no readable multitouch input device found";
            if (!ensureTouchProcessLocked()) return "Unavailable • shell cannot read " + device.path;
            return "Ready • " + device.name + " • " + device.maxX + "×" + device.maxY;
        }
    }

    @Override
    public String waitForTouchEvent(int timeoutMs) {
        timeoutMs = clamp(timeoutMs, 100, 2500);
        synchronized (touchLock) {
            if (!ensureTouchProcessLocked()) return "ERR:Touch input unavailable";
            long deadline = System.currentTimeMillis() + timeoutMs;
            try {
                while (System.currentTimeMillis() < deadline) {
                    if (touchProcess == null || !touchProcess.isAlive()) {
                        stopTouchMonitorLocked();
                        return "ERR:Touch input monitor stopped";
                    }
                    if (touchReader != null && touchReader.ready()) {
                        String line = touchReader.readLine();
                        if (line == null) {
                            stopTouchMonitorLocked();
                            return "ERR:Touch input monitor ended";
                        }
                        String completed = touchAccumulator.consume(line, touchDevice);
                        if (completed != null) return completed;
                    } else {
                        try { Thread.sleep(8L); } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return "";
                        }
                    }
                }
            } catch (Throwable t) {
                stopTouchMonitorLocked();
                return "ERR:" + t.getClass().getSimpleName();
            }
            return "";
        }
    }

    @Override
    public String wakeScreen() {
        return errorFrom(run("/system/bin/input", "keyevent", "224"));
    }

    @Override
    public String dispatchMediaKey(int keyCode) {
        // Keep the Shizuku bridge restricted to the media keys AODControl exposes.
        if (keyCode != 85 && keyCode != 87 && keyCode != 88) return "Unsupported media key";
        return errorFrom(run("/system/bin/input", "keyevent", Integer.toString(keyCode)));
    }

    @Override
    public void destroy() {
        synchronized (touchLock) { stopTouchMonitorLocked(); }
        System.exit(0);
    }

    private TouchDevice ensureTouchDeviceLocked() {
        if (touchDevice != null) return touchDevice;
        CommandResult listing = run("/system/bin/getevent", "-lp");
        if (listing.exitCode != 0 || listing.output.isEmpty()) return null;
        touchDevice = parseTouchDevice(listing.output);
        return touchDevice;
    }

    private boolean ensureTouchProcessLocked() {
        if (touchProcess != null && touchProcess.isAlive() && touchReader != null) return true;
        stopTouchMonitorLocked();
        TouchDevice device = ensureTouchDeviceLocked();
        if (device == null) return false;
        try {
            ProcessBuilder builder = new ProcessBuilder("/system/bin/getevent", "-lt", device.path);
            builder.redirectErrorStream(true);
            touchProcess = builder.start();
            touchReader = new BufferedReader(new InputStreamReader(
                    touchProcess.getInputStream(), StandardCharsets.UTF_8));
            touchAccumulator.reset();
            try { Thread.sleep(35L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if (!touchProcess.isAlive()) {
                stopTouchMonitorLocked();
                return false;
            }
            return true;
        } catch (Throwable t) {
            stopTouchMonitorLocked();
            return false;
        }
    }

    private void stopTouchMonitorLocked() {
        touchAccumulator.reset();
        if (touchReader != null) {
            try { touchReader.close(); } catch (Throwable ignored) {}
        }
        if (touchProcess != null) {
            try { touchProcess.destroy(); } catch (Throwable ignored) {}
        }
        touchReader = null;
        touchProcess = null;
    }

    private static TouchDevice parseTouchDevice(String output) {
        String currentPath = null;
        String currentName = "Touchscreen";
        int maxX = -1;
        int maxY = -1;
        List<TouchDevice> candidates = new ArrayList<>();

        Pattern pathPattern = Pattern.compile("(?:add device \\d+:\\s*)?(/dev/input/event\\d+)");
        Pattern namePattern = Pattern.compile("name:\\s*\\\"([^\\\"]+)\\\"");
        Pattern maxPattern = Pattern.compile("max\\s+(-?\\d+)");

        for (String line : output.split("\\r?\\n")) {
            Matcher pathMatcher = pathPattern.matcher(line);
            if (pathMatcher.find()) {
                if (currentPath != null && maxX > 0 && maxY > 0) {
                    candidates.add(new TouchDevice(currentPath, currentName, maxX, maxY));
                }
                currentPath = pathMatcher.group(1);
                currentName = "Touchscreen";
                maxX = -1;
                maxY = -1;
                continue;
            }
            if (currentPath == null) continue;
            Matcher nameMatcher = namePattern.matcher(line);
            if (nameMatcher.find()) currentName = nameMatcher.group(1);
            if (line.contains("ABS_MT_POSITION_X") || line.matches(".*\\bABS_X\\b.*")) {
                Matcher m = maxPattern.matcher(line);
                if (m.find()) maxX = parseIntSafe(m.group(1), maxX);
            }
            if (line.contains("ABS_MT_POSITION_Y") || line.matches(".*\\bABS_Y\\b.*")) {
                Matcher m = maxPattern.matcher(line);
                if (m.find()) maxY = parseIntSafe(m.group(1), maxY);
            }
        }
        if (currentPath != null && maxX > 0 && maxY > 0) {
            candidates.add(new TouchDevice(currentPath, currentName, maxX, maxY));
        }
        if (candidates.isEmpty()) return null;
        for (TouchDevice c : candidates) {
            String n = c.name.toLowerCase(Locale.US);
            if (n.contains("touch") || n.contains("tsp") || n.contains("goodix")
                    || n.contains("focal") || n.contains("synaptics")) return c;
        }
        return candidates.get(0);
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
        String[] priority = new String[] {"topResumedActivity", "mResumedActivity", "mCurrentFocus", "mFocusedApp"};
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

    private static long parseHex(String value, long fallback) {
        try {
            value = value.trim();
            if (value.startsWith("0x") || value.startsWith("0X")) value = value.substring(2);
            return Long.parseUnsignedLong(value, 16);
        } catch (Throwable ignored) { return fallback; }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static final class TouchDevice {
        final String path;
        final String name;
        final int maxX;
        final int maxY;

        TouchDevice(String path, String name, int maxX, int maxY) {
            this.path = path;
            this.name = name == null || name.isEmpty() ? "Touchscreen" : name;
            this.maxX = maxX;
            this.maxY = maxY;
        }
    }

    private static final class TouchAccumulator {
        private boolean active;
        private boolean releasePending;
        private boolean started;
        private int x = -1;
        private int y = -1;
        private int startX;
        private int startY;
        private int lastX;
        private int lastY;
        private long downAt;

        String consume(String line, TouchDevice device) {
            if (line == null) return null;
            if (line.contains("ABS_MT_TRACKING_ID")) {
                String token = lastToken(line);
                long id = parseHex(token, -2L);
                if (id == 0xffffffffL || id == 0xffffffffffffffffL) {
                    if (active || started) releasePending = true;
                    active = false;
                } else if (id >= 0) {
                    beginContact();
                }
            } else if (line.contains("BTN_TOUCH")) {
                String token = lastToken(line);
                if ("DOWN".equalsIgnoreCase(token) || "00000001".equals(token)) beginContact();
                if ("UP".equalsIgnoreCase(token) || "00000000".equals(token)) {
                    if (active || started) releasePending = true;
                    active = false;
                }
            } else if (line.contains("ABS_MT_POSITION_X") || line.matches(".*\\bABS_X\\b.*")) {
                long value = parseHex(lastToken(line), -1L);
                if (value >= 0 && value <= Integer.MAX_VALUE) x = (int) value;
            } else if (line.contains("ABS_MT_POSITION_Y") || line.matches(".*\\bABS_Y\\b.*")) {
                long value = parseHex(lastToken(line), -1L);
                if (value >= 0 && value <= Integer.MAX_VALUE) y = (int) value;
            }

            if (line.contains("SYN_REPORT")) {
                if ((active || releasePending) && x >= 0 && y >= 0) {
                    if (!started) {
                        started = true;
                        startX = x;
                        startY = y;
                        downAt = System.currentTimeMillis();
                    }
                    lastX = x;
                    lastY = y;
                }
                if (releasePending && started) {
                    long duration = Math.max(1L, System.currentTimeMillis() - downAt);
                    String payload = startX + "," + startY + "," + lastX + "," + lastY
                            + "," + device.maxX + "," + device.maxY + "," + duration;
                    resetContact();
                    return payload;
                }
                if (releasePending) resetContact();
            }
            return null;
        }

        private void beginContact() {
            active = true;
            releasePending = false;
            started = false;
            x = -1;
            y = -1;
            downAt = System.currentTimeMillis();
        }

        void reset() { resetContact(); }

        private void resetContact() {
            active = false;
            releasePending = false;
            started = false;
            x = -1;
            y = -1;
            startX = startY = lastX = lastY = 0;
            downAt = 0L;
        }

        private static String lastToken(String line) {
            String trimmed = line.trim();
            int i = trimmed.lastIndexOf(' ');
            return i < 0 ? trimmed : trimmed.substring(i + 1);
        }
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
