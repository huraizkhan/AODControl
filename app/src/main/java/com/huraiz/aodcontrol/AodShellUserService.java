package com.huraiz.aodcontrol;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
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

    // Notification-free background engine. This runs inside Shizuku's UserService
    // process, so Android does not require AODControl to keep a foreground service.
    private volatile boolean backgroundStop;
    private volatile boolean gestureEngineEnabled;
    private volatile boolean automationEngineEnabled;
    private volatile String backgroundStatus = "Shizuku background idle";
    private Thread gestureEngineThread;
    private Thread automationEngineThread;

    private volatile int engineActiveHeight = 100;
    private volatile int engineEdgeWidth = 20;
    private volatile int engineSensitivity = 70;
    private volatile int[] engineActions = new int[10];
    private int enginePendingTapCount;
    private long engineLastTapAt;
    private TouchSample engineLastTap;

    private volatile int defaultBehavior;
    private volatile int defaultOpacity = 255;
    private volatile boolean chargingEnabled;
    private volatile int chargingBehavior;
    private volatile int chargingOpacity = 255;
    private volatile boolean navigationEnabled;
    private volatile int navigationBehavior;
    private volatile int navigationOpacity = 255;
    private volatile Set<String> navigationPackages = new HashSet<>();
    private volatile boolean outdoorEnabled;
    private volatile int outdoorBehavior;
    private volatile int outdoorOpacity = 255;
    private volatile int outdoorStart = 8 * 60;
    private volatile int outdoorEnd = 18 * 60;
    private volatile boolean nightEnabled;
    private volatile int nightBehavior;
    private volatile int nightOpacity = 255;
    private volatile int nightStart = 19 * 60;
    private volatile int nightEnd = 6 * 60;
    private volatile String lastRemoteAutomationKey = "";
    private volatile boolean remoteRefreshPending;

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
    public String refreshNativeAod() {
        // Pixel/SystemUI keeps the active Doze session's scrim cached. Merely
        // rewriting doze_always_on does not rebuild that session, so AOFP opacity
        // changes can otherwise wait until the next real screen-on/off cycle.
        //
        // Shell cannot use `cmd dreams start-dreaming/stop-dreaming` on modern
        // Android because DreamShellCommand is root-only. Instead, perform the
        // smallest power-state pulse available to Shizuku's shell identity:
        // disable AOD -> wake -> restore AOD -> sleep. The wake window is kept
        // deliberately tiny so the panel normally transitions straight back into
        // native AOD rather than remaining on the lock screen.
        String current = getSetting("secure", AodSettings.DOZE_ALWAYS_ON);
        if (!"1".equals(current)) return "Native AOD is disabled";

        String error = putSetting("secure", AodSettings.DOZE_ALWAYS_ON, "0");
        if (error != null && !error.isEmpty()) return error;

        try { Thread.sleep(35L); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            putSetting("secure", AodSettings.DOZE_ALWAYS_ON, "1");
            return "AOD refresh interrupted";
        }

        CommandResult wake = run("/system/bin/input", "keyevent", "224");
        if (wake.exitCode != 0) {
            putSetting("secure", AodSettings.DOZE_ALWAYS_ON, "1");
            return errorFrom(wake);
        }

        // Give PowerManager just enough time to leave the old Doze session before
        // re-enabling AOD and immediately requesting sleep again.
        try { Thread.sleep(45L); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            putSetting("secure", AodSettings.DOZE_ALWAYS_ON, "1");
            run("/system/bin/input", "keyevent", "223");
            return "AOD refresh interrupted";
        }

        error = putSetting("secure", AodSettings.DOZE_ALWAYS_ON, "1");
        if (error != null && !error.isEmpty()) {
            putSetting("secure", AodSettings.DOZE_ALWAYS_ON, "1");
            run("/system/bin/input", "keyevent", "223");
            return error;
        }

        try { Thread.sleep(25L); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        CommandResult sleep = run("/system/bin/input", "keyevent", "223");
        if (sleep.exitCode != 0) return errorFrom(sleep);
        return "";
    }

    @Override
    public String configureGestureEngine(boolean enabled, int activeHeight, int edgeWidth,
                                         int sensitivity, int[] actions) {
        gestureEngineEnabled = enabled;
        engineActiveHeight = clamp(activeHeight, 40, 100);
        engineEdgeWidth = clamp(edgeWidth, 8, 35);
        engineSensitivity = clamp(sensitivity, 1, 100);
        engineActions = actions == null ? new int[10] : copyActions(actions);
        backgroundStop = false;
        if (enabled) startGestureEngine();
        updateBackgroundStatus();
        return "";
    }

    @Override
    public String configureAutomationEngine(boolean enabled,
            int defaultBehavior, int defaultOpacity,
            boolean chargingEnabled, int chargingBehavior, int chargingOpacity,
            boolean navigationEnabled, int navigationBehavior, int navigationOpacity, String[] navigationPackages,
            boolean outdoorEnabled, int outdoorBehavior, int outdoorOpacity, int outdoorStart, int outdoorEnd,
            boolean nightEnabled, int nightBehavior, int nightOpacity, int nightStart, int nightEnd) {
        automationEngineEnabled = enabled;
        this.defaultBehavior = sanitizeBehavior(defaultBehavior);
        this.defaultOpacity = clamp(defaultOpacity, 0, 255);
        this.chargingEnabled = chargingEnabled;
        this.chargingBehavior = sanitizeBehavior(chargingBehavior);
        this.chargingOpacity = clamp(chargingOpacity, 0, 255);
        this.navigationEnabled = navigationEnabled;
        this.navigationBehavior = sanitizeBehavior(navigationBehavior);
        this.navigationOpacity = clamp(navigationOpacity, 0, 255);
        HashSet<String> packages = new HashSet<>();
        if (navigationPackages != null) {
            for (String pkg : navigationPackages) {
                if (pkg != null && pkg.matches("[A-Za-z0-9_.]+")) packages.add(pkg);
            }
        }
        this.navigationPackages = packages;
        this.outdoorEnabled = outdoorEnabled;
        this.outdoorBehavior = sanitizeBehavior(outdoorBehavior);
        this.outdoorOpacity = clamp(outdoorOpacity, 0, 255);
        this.outdoorStart = clamp(outdoorStart, 0, 1439);
        this.outdoorEnd = clamp(outdoorEnd, 0, 1439);
        this.nightEnabled = nightEnabled;
        this.nightBehavior = sanitizeBehavior(nightBehavior);
        this.nightOpacity = clamp(nightOpacity, 0, 255);
        this.nightStart = clamp(nightStart, 0, 1439);
        this.nightEnd = clamp(nightEnd, 0, 1439);
        backgroundStop = false;
        if (enabled) startAutomationEngine();
        updateBackgroundStatus();
        return "";
    }

    @Override
    public String getBackgroundEngineStatus() {
        updateBackgroundStatus();
        return backgroundStatus;
    }

    @Override
    public String stopBackgroundEngine() {
        backgroundStop = true;
        gestureEngineEnabled = false;
        automationEngineEnabled = false;
        enginePendingTapCount = 0;
        synchronized (touchLock) { stopTouchMonitorLocked(); }
        backgroundStatus = "Shizuku background stopped";
        return "";
    }

    private synchronized void startGestureEngine() {
        if (gestureEngineThread != null && gestureEngineThread.isAlive()) return;
        gestureEngineThread = new Thread(this::gestureEngineLoop, "AODControl-Gestures");
        gestureEngineThread.setDaemon(true);
        gestureEngineThread.start();
    }

    private synchronized void startAutomationEngine() {
        if (automationEngineThread != null && automationEngineThread.isAlive()) return;
        automationEngineThread = new Thread(this::automationEngineLoop, "AODControl-Automation");
        automationEngineThread.setDaemon(true);
        automationEngineThread.start();
    }

    private void gestureEngineLoop() {
        while (!backgroundStop) {
            if (!gestureEngineEnabled) {
                sleepQuiet(350L);
                continue;
            }
            String payload = waitForTouchEvent(220);
            finalizeRemoteTapIfExpired();
            if (payload == null || payload.isEmpty() || payload.startsWith("ERR:")) {
                if (payload != null && payload.startsWith("ERR:")) backgroundStatus = payload.substring(4);
                continue;
            }
            // Only evaluate power state after a completed touch. This avoids
            // repeated dumpsys calls while the phone is idle or being used normally.
            if (isScreenInteractiveShell()) continue;
            TouchSample sample = TouchSample.parse(payload);
            if (sample == null || !sample.startsInsideActiveHeight(engineActiveHeight)) continue;
            if (sample.isTap(engineSensitivity)) {
                handleRemoteTap(sample);
            } else {
                String gesture = sample.movementGesture(engineEdgeWidth, engineSensitivity);
                int index = gestureIndex(gesture);
                if (index >= 0) executeRemoteGesture(actionAt(index), sample);
            }
        }
    }

    private void handleRemoteTap(TouchSample sample) {
        long now = System.currentTimeMillis();
        if (enginePendingTapCount == 0 || now - engineLastTapAt > 430L || !sample.closeTo(engineLastTap)) {
            finalizeRemoteTap();
            enginePendingTapCount = 1;
        } else {
            enginePendingTapCount++;
        }
        engineLastTapAt = now;
        engineLastTap = sample;
        if (enginePendingTapCount >= 3 || (enginePendingTapCount == 2 && actionAt(1) == AppPrefs.GESTURE_ACTION_NONE)) {
            finalizeRemoteTap();
        }
    }

    private void finalizeRemoteTapIfExpired() {
        if (enginePendingTapCount > 0 && System.currentTimeMillis() - engineLastTapAt > 440L) finalizeRemoteTap();
    }

    private void finalizeRemoteTap() {
        int count = enginePendingTapCount;
        TouchSample sample = engineLastTap;
        enginePendingTapCount = 0;
        engineLastTapAt = 0L;
        engineLastTap = null;
        if (count >= 3) executeRemoteGesture(actionAt(1), sample);
        else if (count == 2) executeRemoteGesture(actionAt(0), sample);
    }

    private void executeRemoteGesture(int action, TouchSample sample) {
        if (action == AppPrefs.GESTURE_ACTION_NONE) return;
        switch (action) {
            case AppPrefs.GESTURE_ACTION_TORCH:
                sendAppGestureAction(action, 0);
                break;
            case AppPrefs.GESTURE_ACTION_PLAY_PAUSE:
                dispatchMediaKey(85);
                break;
            case AppPrefs.GESTURE_ACTION_NEXT_TRACK:
                dispatchMediaKey(87);
                break;
            case AppPrefs.GESTURE_ACTION_PREVIOUS_TRACK:
                dispatchMediaKey(88);
                break;
            case AppPrefs.GESTURE_ACTION_VOLUME_UP:
                run("/system/bin/input", "keyevent", "24");
                break;
            case AppPrefs.GESTURE_ACTION_VOLUME_DOWN:
                run("/system/bin/input", "keyevent", "25");
                break;
            case AppPrefs.GESTURE_ACTION_WAKE_SCREEN:
                wakeScreen();
                break;
            case AppPrefs.GESTURE_ACTION_VOLUME_SLIDER:
                if (sample != null) {
                    float fraction = sample.verticalFractionUp();
                    int steps = Math.max(1, Math.min(10, Math.round(Math.abs(fraction) * 10f)));
                    int key = fraction >= 0 ? 24 : 25;
                    for (int i = 0; i < steps; i++) run("/system/bin/input", "keyevent", Integer.toString(key));
                }
                break;
            case AppPrefs.GESTURE_ACTION_WAKE_AOD:
                setRemoteAodVisible(true);
                break;
            case AppPrefs.GESTURE_ACTION_SLEEP_AOD:
                setRemoteAodVisible(false);
                break;
            case AppPrefs.GESTURE_ACTION_TOGGLE_AOD:
                setRemoteAodVisible(isRemoteAodBlank());
                break;
            default:
                break;
        }
        backgroundStatus = "Shizuku background active • last gesture " + AppPrefs.gestureActionLabel(action);
    }

    private void sendAppGestureAction(int action, int value) {
        run("/system/bin/am", "broadcast", "--user", "current",
                "-a", "com.huraiz.aodcontrol.GESTURE_ACTION",
                "-n", "com.huraiz.aodcontrol/.GestureActionReceiver",
                "--ei", "action", Integer.toString(action),
                "--ei", "value", Integer.toString(value));
    }

    private String setRemoteAodVisible(boolean visible) {
        String error = setUniformDimming(visible ? 0 : 255, 0);
        if (error != null && !error.isEmpty()) return error;
        if (!isScreenInteractiveShell()) return refreshNativeAod();
        return "";
    }

    private boolean isRemoteAodBlank() {
        String constants = getSetting("global", AodSettings.AOD_CONSTANTS);
        String value = AodSettings.getValue(constants, AodSettings.DIMMING_KEY);
        if (value == null || value.isEmpty()) return false;
        String[] parts = value.split(":");
        if (parts.length < 2) return false;
        for (String part : parts) {
            try { if (Integer.parseInt(part.trim()) < 250) return false; }
            catch (Throwable ignored) { return false; }
        }
        return true;
    }

    private void automationEngineLoop() {
        while (!backgroundStop) {
            if (!automationEngineEnabled) {
                sleepQuiet(500L);
                continue;
            }
            RemoteState state = resolveRemoteState();
            String key = state.reason + "|" + state.behavior + "|" + state.opacity;
            if (!key.equals(lastRemoteAutomationKey)) {
                String error = applyRemoteBehavior(state.behavior, state.opacity);
                if (error == null || error.isEmpty()) {
                    lastRemoteAutomationKey = key;
                    remoteRefreshPending = true;
                }
            }
            if (remoteRefreshPending && !isScreenInteractiveShell()) {
                String error = refreshNativeAod();
                if (error == null || error.isEmpty()) remoteRefreshPending = false;
            }
            updateBackgroundStatus();
            sleepQuiet(chargingEnabled ? 700L : (navigationEnabled ? 2500L : 15000L));
        }
    }

    private RemoteState resolveRemoteState() {
        if (chargingEnabled && isChargingShell()) {
            return new RemoteState("Charging", chargingBehavior, chargingOpacity);
        }
        if (navigationEnabled && !navigationPackages.isEmpty()) {
            String pkg = getForegroundPackage();
            if (pkg != null && navigationPackages.contains(pkg)) {
                return new RemoteState("Navigation", navigationBehavior, navigationOpacity);
            }
        }
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (outdoorEnabled && inRange(now, outdoorStart, outdoorEnd)) {
            return new RemoteState("Outdoor hours", outdoorBehavior, outdoorOpacity);
        }
        if (nightEnabled && inRange(now, nightStart, nightEnd)) {
            return new RemoteState("Night mode", nightBehavior, nightOpacity);
        }
        return new RemoteState("Default", defaultBehavior, defaultOpacity);
    }

    private String applyRemoteBehavior(int behavior, int opacity) {
        if (behavior == AppPrefs.BEHAVIOR_SYSTEM) return removeDimmingOverride();
        String error = putSetting("secure", AodSettings.DOZE_ALWAYS_ON, "1");
        if (error != null && !error.isEmpty()) return error;
        if (behavior == AppPrefs.BEHAVIOR_AOFP) {
            putSetting("secure", AodSettings.SCREEN_OFF_UDFPS_ENABLED, "1");
            return setUniformDimming(255, 0);
        }
        return setUniformDimming(clamp(opacity, 0, 255), 0);
    }

    private boolean isChargingShell() {
        CommandResult result = run("/system/bin/dumpsys", "battery");
        String v = result.output.toLowerCase(Locale.US);
        return v.contains("ac powered: true") || v.contains("usb powered: true")
                || v.contains("wireless powered: true") || v.contains("dock powered: true")
                || v.matches("(?s).*status:\\s*(2|5)\\b.*");
    }

    private boolean isScreenInteractiveShell() {
        CommandResult result = run("/system/bin/dumpsys", "power");
        String v = result.output;
        if (v.contains("mWakefulness=Awake") || v.contains("mInteractive=true")) return true;
        if (v.contains("mWakefulness=Dozing") || v.contains("mWakefulness=Asleep") || v.contains("mInteractive=false")) return false;
        return false;
    }

    private static boolean inRange(int now, int start, int end) {
        if (start == end) return true;
        if (start < end) return now >= start && now < end;
        return now >= start || now < end;
    }

    private void updateBackgroundStatus() {
        if (backgroundStop) {
            backgroundStatus = "Shizuku background stopped";
            return;
        }
        if (gestureEngineEnabled && automationEngineEnabled) backgroundStatus = "Shizuku background active • gestures + automation";
        else if (gestureEngineEnabled) backgroundStatus = "Shizuku background active • gestures";
        else if (automationEngineEnabled) backgroundStatus = "Shizuku background active • automation";
        else backgroundStatus = "Shizuku background idle";
    }

    private int actionAt(int index) {
        int[] actions = engineActions;
        return index >= 0 && index < actions.length ? actions[index] : AppPrefs.GESTURE_ACTION_NONE;
    }

    private static int[] copyActions(int[] src) {
        int[] out = new int[10];
        if (src != null) System.arraycopy(src, 0, out, 0, Math.min(out.length, src.length));
        return out;
    }

    private static int gestureIndex(String gesture) {
        if (AppPrefs.GESTURE_SWIPE_LEFT_TO_RIGHT.equals(gesture)) return 2;
        if (AppPrefs.GESTURE_SWIPE_RIGHT_TO_LEFT.equals(gesture)) return 3;
        if (AppPrefs.GESTURE_SWIPE_UP.equals(gesture)) return 4;
        if (AppPrefs.GESTURE_SWIPE_DOWN.equals(gesture)) return 5;
        if (AppPrefs.GESTURE_LEFT_EDGE_UP.equals(gesture)) return 6;
        if (AppPrefs.GESTURE_LEFT_EDGE_DOWN.equals(gesture)) return 7;
        if (AppPrefs.GESTURE_RIGHT_EDGE_UP.equals(gesture)) return 8;
        if (AppPrefs.GESTURE_RIGHT_EDGE_DOWN.equals(gesture)) return 9;
        return -1;
    }

    private static int sanitizeBehavior(int behavior) {
        if (behavior == AppPrefs.BEHAVIOR_AOFP || behavior == AppPrefs.BEHAVIOR_MANUAL) return behavior;
        return AppPrefs.BEHAVIOR_SYSTEM;
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static final class RemoteState {
        final String reason;
        final int behavior;
        final int opacity;
        RemoteState(String reason, int behavior, int opacity) {
            this.reason = reason;
            this.behavior = behavior;
            this.opacity = opacity;
        }
    }

    @Override
    public void destroy() {
        backgroundStop = true;
        gestureEngineEnabled = false;
        automationEngineEnabled = false;
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
