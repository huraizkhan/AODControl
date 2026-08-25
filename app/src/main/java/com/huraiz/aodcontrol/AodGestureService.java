package com.huraiz.aodcontrol;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AodGestureService extends Service implements ShizukuBridge.Listener {
    public static final String ACTION_REFRESH = "com.huraiz.aodcontrol.action.REFRESH_GESTURES";
    private static final String CHANNEL_ID = "aod_active";
    private static final int NOTIFICATION_ID = 1030;

    private static volatile boolean running;
    private static volatile String monitorStatus = "Stopped";

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService monitorExecutor;
    private volatile boolean monitorLoopRunning;

    private AudioManager audioManager;
    private CameraManager cameraManager;
    private String torchCameraId;
    private volatile boolean torchOn;

    private volatile int pendingTapCount;
    private long lastTapAt;
    private TouchSample lastTap;

    private final Runnable finalizeTapSequence = () -> {
        int count = pendingTapCount;
        pendingTapCount = 0;
        lastTapAt = 0L;
        TouchSample sample = lastTap;
        lastTap = null;
        if (count >= 3) executeGesture(AppPrefs.GESTURE_TRIPLE_TAP, sample);
        else if (count == 2) executeGesture(AppPrefs.GESTURE_DOUBLE_TAP, sample);
    };

    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (torchCameraId == null || torchCameraId.equals(cameraId)) {
                torchCameraId = cameraId;
                torchOn = enabled;
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager != null) {
            try { cameraManager.registerTorchCallback(torchCallback, main); } catch (Throwable ignored) {}
        }
        ShizukuBridge.addListener(this);
        createChannel();
        startForeground(NOTIFICATION_ID, notification("Starting AOD gesture monitor…"));
        startMonitorLoop();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!shouldRun(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startMonitorLoop();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        monitorLoopRunning = false;
        main.removeCallbacksAndMessages(null);
        ShizukuBridge.removeListener(this);
        if (cameraManager != null) {
            try { cameraManager.unregisterTorchCallback(torchCallback); } catch (Throwable ignored) {}
        }
        if (monitorExecutor != null) monitorExecutor.shutdownNow();
        monitorStatus = "Stopped";
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onShizukuStateChanged() {
        if (!ShizukuBridge.isReady()) {
            setStatus("Waiting for Shizuku");
        } else {
            startMonitorLoop();
        }
    }

    private synchronized void startMonitorLoop() {
        if (!shouldRun(this) || monitorLoopRunning) return;
        if (monitorExecutor == null || monitorExecutor.isShutdown()) {
            monitorExecutor = Executors.newSingleThreadExecutor();
        }
        monitorLoopRunning = true;
        monitorExecutor.execute(() -> {
            try {
                runMonitorLoop();
            } finally {
                monitorLoopRunning = false;
            }
        });
    }

    private void runMonitorLoop() {
        String lastCapability = null;
        while (running && shouldRun(this) && !Thread.currentThread().isInterrupted()) {
            IAodShellService shell = ShizukuBridge.getService();
            if (!ShizukuBridge.isReady() || shell == null) {
                setStatus("Waiting for Shizuku");
                sleep(700L);
                continue;
            }

            if (lastCapability == null) {
                try { lastCapability = shell.getTouchCapabilities(); }
                catch (Throwable t) { lastCapability = "Unavailable"; }
                if (lastCapability == null || lastCapability.startsWith("Unavailable")) {
                    setStatus(lastCapability == null ? "Touch input unavailable" : lastCapability);
                    sleep(1500L);
                    lastCapability = null;
                    continue;
                }
                setStatus("Monitoring native AOD touch");
            }

            boolean ambientAtStart = !isScreenInteractive();
            String payload;
            try {
                payload = shell.waitForTouchEvent(1500);
            } catch (Throwable t) {
                lastCapability = null;
                setStatus("Touch monitor reconnecting");
                sleep(450L);
                continue;
            }
            if (payload == null || payload.isEmpty()) continue;
            if (payload.startsWith("ERR:")) {
                lastCapability = null;
                setStatus(payload.substring(4));
                sleep(700L);
                continue;
            }

            TouchSample sample = TouchSample.parse(payload);
            if (sample == null) continue;
            boolean sequenceStartedOnAod = ambientAtStart || !isScreenInteractive() || pendingTapCount > 0;
            if (!sequenceStartedOnAod) continue;

            int activeHeight = AppPrefs.getGestureActiveHeightPercent(this);
            if (!sample.startsInsideActiveHeight(activeHeight)) continue;
            int sensitivity = AppPrefs.getGestureSensitivityPercent(this);
            if (sample.isTap(sensitivity)) {
                main.post(() -> onTap(sample));
            } else {
                int edgeWidth = AppPrefs.getGestureEdgeWidthPercent(this);
                String gesture = sample.movementGesture(edgeWidth, sensitivity);
                if (gesture != null) main.post(() -> executeGesture(gesture, sample));
            }
        }
    }

    private void onTap(TouchSample sample) {
        long now = SystemClock.uptimeMillis();
        if (pendingTapCount == 0 || now - lastTapAt > 430L || !sample.closeTo(lastTap)) {
            if (pendingTapCount >= 2) finalizeTapSequence.run();
            pendingTapCount = 1;
        } else {
            pendingTapCount++;
        }
        lastTapAt = now;
        lastTap = sample;
        main.removeCallbacks(finalizeTapSequence);
        if (pendingTapCount >= 3) {
            finalizeTapSequence.run();
        } else if (pendingTapCount == 2
                && AppPrefs.getGestureAction(this, AppPrefs.GESTURE_TRIPLE_TAP) == AppPrefs.GESTURE_ACTION_NONE) {
            finalizeTapSequence.run();
        } else {
            main.postDelayed(finalizeTapSequence, 440L);
        }
    }

    private void executeGesture(String gesture, TouchSample sample) {
        if (gesture == null) return;
        int action = AppPrefs.getGestureAction(this, gesture);
        if (action == AppPrefs.GESTURE_ACTION_NONE) return;
        String result = performAction(action, sample);
        String label = AppPrefs.gestureActionLabel(action);
        setStatus(result == null ? "Last action • " + label : result);
    }

    private String performAction(int action, TouchSample sample) {
        switch (action) {
            case AppPrefs.GESTURE_ACTION_TORCH:
                return toggleTorch();
            case AppPrefs.GESTURE_ACTION_PLAY_PAUSE:
                return dispatchMedia(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                        ? "Last action • Play / pause" : "Media action failed";
            case AppPrefs.GESTURE_ACTION_NEXT_TRACK:
                return dispatchMedia(KeyEvent.KEYCODE_MEDIA_NEXT)
                        ? "Last action • Next track" : "Media action failed";
            case AppPrefs.GESTURE_ACTION_PREVIOUS_TRACK:
                return dispatchMedia(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                        ? "Last action • Previous track" : "Media action failed";
            case AppPrefs.GESTURE_ACTION_VOLUME_UP:
                adjustVolume(1);
                return "Last action • Volume up";
            case AppPrefs.GESTURE_ACTION_VOLUME_DOWN:
                adjustVolume(-1);
                return "Last action • Volume down";
            case AppPrefs.GESTURE_ACTION_WAKE_SCREEN:
                wakeScreen();
                return "Last action • Wake screen";
            case AppPrefs.GESTURE_ACTION_VOLUME_SLIDER:
                if (sample != null) applyVolumeSlide(sample);
                return "Last action • Volume slider";
            case AppPrefs.GESTURE_ACTION_WAKE_AOD:
                return setAodVisible(true);
            case AppPrefs.GESTURE_ACTION_SLEEP_AOD:
                return setAodVisible(false);
            case AppPrefs.GESTURE_ACTION_TOGGLE_AOD:
                return toggleAod();
            default:
                return null;
        }
    }

    private void applyVolumeSlide(TouchSample sample) {
        if (audioManager == null) return;
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        float fraction = sample.verticalFractionUp();
        int delta = Math.round(fraction * Math.max(4, max));
        if (delta == 0) delta = fraction > 0 ? 1 : -1;
        int target = Math.max(0, Math.min(max, current + delta));
        try { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0); } catch (Throwable ignored) {}
    }

    private void adjustVolume(int direction) {
        if (audioManager == null) return;
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    direction > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
        } catch (Throwable ignored) {}
    }

    private boolean dispatchMedia(int keyCode) {
        // Shell input is much more reliable on the lock screen/AOD than a normal
        // app-side media dispatch. Keep AudioManager as a fallback for OEMs that
        // reject shell media keys.
        IAodShellService shell = ShizukuBridge.getService();
        if (shell != null) {
            try {
                String error = shell.dispatchMediaKey(keyCode);
                if (error == null || error.isEmpty()) return true;
            } catch (Throwable ignored) {}
        }
        if (audioManager == null) return false;
        long now = SystemClock.uptimeMillis();
        try {
            audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String toggleAod() {
        IAodShellService shell = ShizukuBridge.getService();
        if (shell == null) return "AOD action needs Shizuku";
        try {
            String constants = shell.getSetting("global", AodSettings.AOD_CONSTANTS);
            String value = AodSettings.getValue(constants, AodSettings.DIMMING_KEY);
            boolean blank = true;
            if (value == null || value.isEmpty()) blank = false;
            else {
                String[] parts = value.split(":");
                if (parts.length < 2) blank = false;
                else {
                    for (String part : parts) {
                        try { if (Integer.parseInt(part.trim()) < 250) { blank = false; break; } }
                        catch (Throwable ignored) { blank = false; break; }
                    }
                }
            }
            return setAodVisible(blank);
        } catch (Throwable t) {
            return "AOD action failed";
        }
    }

    private String setAodVisible(boolean visible) {
        IAodShellService shell = ShizukuBridge.getService();
        if (shell == null) return "AOD action needs Shizuku";
        try {
            // Wake/Sleep AOD means visible/blank native AOD; it does not wake the
            // lock screen. Update the scrim, then restart only the native doze
            // session so SystemUI actually re-reads the new opacity immediately.
            String error = shell.setUniformDimming(visible ? 0 : 255, 0);
            if (error != null && !error.isEmpty()) return "AOD action failed";
            error = shell.refreshNativeAod();
            if (error != null && !error.isEmpty()) return "AOD refresh failed • " + error;
            return visible ? "Last action • Wake AOD" : "Last action • Sleep AOD";
        } catch (Throwable t) {
            return "AOD action failed";
        }
    }

    private String toggleTorch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return "Torch needs Camera permission";
        }
        if (cameraManager == null) return "Torch unavailable";
        try {
            String id = findTorchCamera();
            if (id == null) return "Torch unavailable";
            boolean target = !torchOn;
            cameraManager.setTorchMode(id, target);
            torchOn = target;
            return "Last action • Torch " + (target ? "on" : "off");
        } catch (Throwable t) {
            return "Torch action failed";
        }
    }

    private String findTorchCamera() throws CameraAccessException {
        if (torchCameraId != null) return torchCameraId;
        for (String id : cameraManager.getCameraIdList()) {
            Boolean flash = cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (Boolean.TRUE.equals(flash)) {
                torchCameraId = id;
                return id;
            }
        }
        return null;
    }

    private void wakeScreen() {
        IAodShellService shell = ShizukuBridge.getService();
        if (shell != null) {
            try { shell.wakeScreen(); } catch (Throwable ignored) {}
        }
    }

    private boolean isScreenInteractive() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    private void setStatus(String value) {
        monitorStatus = value == null ? "" : value;
        updateNotification(monitorStatus);
    }

    private static boolean shouldRun(Context context) {
        return AppPrefs.isGesturesEnabled(context) && AppPrefs.anyGestureActionConfigured(context);
    }

    public static boolean isRunning() { return running; }

    public static String statusText(Context context) {
        if (!AppPrefs.isGesturesEnabled(context)) return "Disabled";
        if (!AppPrefs.anyGestureActionConfigured(context)) return "Enabled • choose at least one gesture action";
        if (!AppPrefs.isForegroundFallbackEnabled(context)) return ShizukuBackgroundEngine.status(context);
        if (!ShizukuBridge.isReady()) return "Waiting for Shizuku";
        return monitorStatus == null || monitorStatus.isEmpty() ? "Starting…" : monitorStatus;
    }

    public static void sync(Context context) {
        Context app = context.getApplicationContext();
        if (!AppPrefs.isForegroundFallbackEnabled(app)) {
            try { app.stopService(new Intent(app, AodGestureService.class)); } catch (Throwable ignored) {}
            ShizukuBackgroundEngine.sync(app);
            return;
        }
        ShizukuBackgroundEngine.stop();
        if (shouldRun(app)) {
            Intent intent = new Intent(app, AodGestureService.class).setAction(ACTION_REFRESH);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent);
                else app.startService(intent);
            } catch (Throwable ignored) {}
        } else {
            try { app.stopService(new Intent(app, AodGestureService.class)); } catch (Throwable ignored) {}
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "AODControl active", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shown only when foreground fallback mode is enabled");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, GestureSettingsActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("AODControl active")
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(content)
                .build();
    }

    private void updateNotification(String text) {
        main.post(() -> {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, notification(text));
        });
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
