package com.huraiz.aodcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SmartDimmingService extends Service implements ShizukuBridge.Listener {
    private static volatile boolean running;
    public static final String ACTION_REFRESH = "com.huraiz.aodcontrol.action.REFRESH_SMART_DIMMING";
    public static final String ACTION_STOP = "com.huraiz.aodcontrol.action.STOP_SMART_DIMMING";

    private static final String CHANNEL_ID = "smart_dimming";
    private static final int NOTIFICATION_ID = 1701;

    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    private final AtomicInteger generation = new AtomicInteger();
    private final Object futuresLock = new Object();
    private final List<ScheduledFuture<?>> activeFutures = new ArrayList<>();

    private PowerManager powerManager;
    private boolean receiverRegistered;
    private String monitorStatus = "Waiting for Shizuku";

    private final IFingerprintTouchCallback fingerprintCallback = new IFingerprintTouchCallback.Stub() {
        @Override
        public void onFingerprintTouch() {
            if (powerManager != null && powerManager.isInteractive()) return;
            handleTrigger(true);
        }

        @Override
        public void onMonitorError(String message) {
            monitorStatus = "Fingerprint monitor unavailable";
            updateNotification();
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                handleTrigger(false);
            } else if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                cancelActiveFade();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        scheduler.setRemoveOnCancelPolicy(true);
        createNotificationChannel();
        startForegroundCompat(buildNotification());
        registerScreenReceiver();
        ShizukuBridge.addListener(this);
        scheduler.execute(this::configureShizukuSide);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (AppPrefs.getMode(this) != AppPrefs.MODE_SMART) {
            stopSelf();
            return START_NOT_STICKY;
        }
        scheduler.execute(this::configureShizukuSide);
        updateNotification();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        cancelActiveFade();
        IAodShellService shell = ShizukuBridge.getService();
        if (shell != null) {
            try { shell.stopTouchMonitor(); } catch (Throwable ignored) {}
        }
        if (receiverRegistered) {
            try { unregisterReceiver(screenReceiver); } catch (Throwable ignored) {}
            receiverRegistered = false;
        }
        ShizukuBridge.removeListener(this);
        scheduler.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onShizukuStateChanged() {
        scheduler.execute(this::configureShizukuSide);
        updateNotification();
    }

    private void configureShizukuSide() {
        IAodShellService shell = ShizukuBridge.getService();
        if (shell == null) {
            monitorStatus = ShizukuBridge.isBinderAlive() ? "Shizuku connecting" : "Start Shizuku";
            updateNotification();
            return;
        }
        try {
            shell.putSetting("secure", AodSettings.DOZE_ALWAYS_ON, "1");
            applyRestingState(shell);
            configureTouchMonitor(shell);
        } catch (Throwable e) {
            monitorStatus = "Shizuku connection lost";
            updateNotification();
        }
    }

    private void configureTouchMonitor(IAodShellService shell) throws RemoteException {
        shell.stopTouchMonitor();
        if (!AppPrefs.isFingerprintCalibrated(this)) {
            monitorStatus = "Screen-off active • fingerprint needs calibration";
            updateNotification();
            return;
        }
        int result = shell.startTouchMonitor(
                AppPrefs.fingerprintX(this),
                AppPrefs.fingerprintY(this),
                AppPrefs.fingerprintRadiusX(this),
                AppPrefs.fingerprintRadiusY(this),
                fingerprintCallback);
        if (result == 0) {
            monitorStatus = "Screen-off + fingerprint triggers active";
        } else {
            monitorStatus = "Screen-off active • fingerprint input unavailable";
        }
        updateNotification();
    }

    private void applyRestingState(IAodShellService shell) throws RemoteException {
        DimmingProfile screenProfile = AppPrefs.profileForTrigger(this, false);
        shell.setUniformDimming(screenProfile.finalOpacity(), 0);
    }

    private void handleTrigger(boolean fingerprint) {
        if (AppPrefs.getMode(this) != AppPrefs.MODE_SMART) return;
        DimmingProfile profile = AppPrefs.profileForTrigger(this, fingerprint);
        startProfile(profile);
    }

    private void startProfile(DimmingProfile profile) {
        cancelActiveFade();
        final int token = generation.incrementAndGet();
        scheduleTracked(() -> {
            if (token != generation.get()) return;
            IAodShellService shell = ShizukuBridge.getService();
            if (shell == null) return;
            try {
                shell.putSetting("secure", AodSettings.DOZE_ALWAYS_ON, "1");
                int[] autoStart = null;
                if (profile.autoOpacity) {
                    autoStart = sanitizeDefaultArray(shell.getSystemDefaultDimmingArray());
                    shell.removeDimmingOverride();
                } else {
                    shell.setUniformDimming(profile.startOpacity, 0);
                }
                scheduleFade(token, profile, autoStart);
            } catch (Throwable ignored) {
            }
        }, 0);
    }

    private void scheduleFade(int token, DimmingProfile profile, int[] autoStart) {
        long totalMs = profile.visibleSeconds * 1000L;
        long delayMs = Math.min(totalMs, profile.delaySeconds * 1000L);
        long fadeMs = Math.max(0L, totalMs - delayMs);

        if (fadeMs <= 0L) {
            scheduleTracked(() -> applyFinal(token, profile), totalMs);
            return;
        }

        int steps = (int) Math.max(8L, Math.min(120L, fadeMs / 120L));
        for (int i = 1; i <= steps; i++) {
            final int step = i;
            final int stepCount = steps;
            long at = delayMs + Math.round(fadeMs * (step / (double) stepCount));
            scheduleTracked(() -> {
                if (token != generation.get()) return;
                IAodShellService shell = ShizukuBridge.getService();
                if (shell == null) return;
                float fraction = step / (float) stepCount;
                try {
                    if (profile.autoOpacity) {
                        int[] base = autoStart == null ? fallbackArray() : autoStart;
                        shell.setDimmingArray(interpolateToBlack(base, fraction));
                    } else {
                        int value = Math.round(profile.startOpacity
                                + (profile.endOpacity - profile.startOpacity) * fraction);
                        shell.setUniformDimming(value, 0);
                    }
                } catch (Throwable ignored) {
                }
            }, at);
        }
    }

    private void applyFinal(int token, DimmingProfile profile) {
        if (token != generation.get()) return;
        IAodShellService shell = ShizukuBridge.getService();
        if (shell == null) return;
        try {
            shell.setUniformDimming(profile.finalOpacity(), 0);
        } catch (Throwable ignored) {
        }
    }

    private void cancelActiveFade() {
        generation.incrementAndGet();
        synchronized (futuresLock) {
            for (ScheduledFuture<?> future : activeFutures) {
                if (future != null) future.cancel(false);
            }
            activeFutures.clear();
        }
    }

    private void scheduleTracked(Runnable runnable, long delayMs) {
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try { runnable.run(); } finally { pruneDoneFutures(); }
        }, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
        synchronized (futuresLock) {
            activeFutures.add(future);
        }
    }

    private void pruneDoneFutures() {
        synchronized (futuresLock) {
            activeFutures.removeIf(f -> f == null || f.isDone() || f.isCancelled());
        }
    }

    private static int[] sanitizeDefaultArray(int[] source) {
        if (source == null || source.length < 2 || source.length > 32) return fallbackArray();
        int[] out = new int[source.length];
        for (int i = 0; i < source.length; i++) {
            // -1 means "keep current" in SystemUI. For a deterministic fade,
            // treat it as a transparent starting scrim while brightness remains
            // under the system's own doze brightness curve.
            out[i] = source[i] < 0 ? 0 : DimmingProfile.clamp(source[i], 0, 255);
        }
        return out;
    }

    private static int[] interpolateToBlack(int[] base, float fraction) {
        fraction = Math.max(0f, Math.min(1f, fraction));
        int[] out = new int[base.length];
        for (int i = 0; i < base.length; i++) {
            int start = DimmingProfile.clamp(base[i], 0, 255);
            out[i] = Math.round(start + (255 - start) * fraction);
        }
        return out;
    }

    private static int[] fallbackArray() {
        return new int[] {0, 0, 0, 0, 0, 0, 0, 0};
    }

    private void registerScreenReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Smart dimming", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps AOD smart dimming active while the screen is off.");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("AOD Control • Smart Dimming")
                .setContentText(monitorStatus)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification() {
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
        } catch (Throwable ignored) {
        }
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    public static boolean isRunning() { return running; }

    public static void start(Context context) {
        Intent intent = new Intent(context, SmartDimmingService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void refresh(Context context) {
        Intent intent = new Intent(context, SmartDimmingService.class).setAction(ACTION_REFRESH);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, SmartDimmingService.class));
    }
}
