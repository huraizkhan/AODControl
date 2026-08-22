package com.huraiz.aodcontrol;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Display;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Universal custom-AOD fallback.
 *
 * It stays idle while the built-in display remains in DOZE/DOZE_SUSPEND. If the
 * device has no native AOD, or a time-limited native AOD eventually transitions
 * to STATE_OFF, it launches CustomAodActivity. This intentionally does not try
 * to replace a working continuous native AOD.
 */
public class UniversalAodService extends Service implements DisplayManager.DisplayListener {
    public static final String ACTION_REFRESH = "com.huraiz.aodcontrol.action.REFRESH_UNIVERSAL_AOD";
    private static final String CHANNEL_ID = "universal_aod";
    private static final int NOTIFICATION_ID = 1042;

    private static volatile boolean running;
    private static volatile boolean customVisible;
    private static volatile boolean launchInProgress;
    private static volatile boolean completedThisSleepCycle;
    private static volatile boolean endingCustom;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private DisplayManager displayManager;
    private CustomAodOverlay customOverlay;
    private boolean usingOverlay;

    private final Runnable offCheck = this::maybeLaunchFallback;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action)) {
                if (!customVisible && !launchInProgress && !endingCustom) {
                    completedThisSleepCycle = false;
                }
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                if (endingCustom) {
                    customVisible = false;
                    main.postDelayed(() -> endingCustom = false, 1000L);
                    return;
                }
                if (customVisible) {
                    // Power button while our AOD is showing means "turn it fully off".
                    completedThisSleepCycle = true;
                    hideCustomAod();
                    return;
                }
                completedThisSleepCycle = false;
                scheduleOffCheck(700L);
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                completedThisSleepCycle = true;
                hideCustomAod();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
        startForeground(NOTIFICATION_ID, notification("Universal AOD ready"));

        customOverlay = new CustomAodOverlay(this);

        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager != null) displayManager.registerDisplayListener(this, main);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!shouldServiceRun(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            updateNotification(statusText(this));
            scheduleOffCheck(250L);
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        try { unregisterReceiver(receiver); } catch (Throwable ignored) {}
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(this); } catch (Throwable ignored) {}
        }
        hideCustomAod();
        launchInProgress = false;
        io.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDisplayAdded(int displayId) {}
    @Override public void onDisplayRemoved(int displayId) {}

    @Override public void onDisplayChanged(int displayId) {
        if (displayId != Display.DEFAULT_DISPLAY) return;
        Display display = displayManager == null ? null : displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display != null && display.getState() == Display.STATE_OFF) scheduleOffCheck(250L);
    }

    private void scheduleOffCheck(long delayMs) {
        main.removeCallbacks(offCheck);
        main.postDelayed(offCheck, delayMs);
    }

    private void maybeLaunchFallback() {
        if (!shouldServiceRun(this) || completedThisSleepCycle || customVisible || launchInProgress || endingCustom) return;

        Display display = displayManager == null ? null : displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null || display.getState() != Display.STATE_OFF) {
            // STATE_DOZE/DOZE_SUSPEND means native AOD is still doing its job.
            return;
        }

        KeyguardManager keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard != null && !keyguard.isKeyguardLocked()) return;

        launchInProgress = true;
        io.execute(() -> {
            boolean launchedByShell = false;
            IAodShellService shell = ShizukuBridge.getService();
            if (shell != null) {
                try {
                    String error = shell.startCustomAod();
                    launchedByShell = error == null || error.isEmpty();
                } catch (Throwable ignored) {}
            }

            final boolean shellStarted = launchedByShell;
            main.post(() -> {
                if (shellStarted) {
                    // CustomAodActivity owns the clock, temporary timer and lock-screen
                    // presentation. Give it a moment to report onCreate().
                    usingOverlay = false;
                    wakeDisplay();
                    main.postDelayed(() -> {
                        launchInProgress = false;
                        if (customVisible) {
                            updateNotification("Custom AOD showing on lock screen");
                        } else {
                            // Some OEMs may still reject the Activity. Retain the old
                            // overlay as a best-effort fallback rather than waking to an
                            // empty lock screen.
                            showOverlayFallback();
                        }
                    }, 700L);
                    return;
                }

                launchInProgress = false;
                showOverlayFallback();
            });
        });
    }

    private void showOverlayFallback() {
        if (completedThisSleepCycle || endingCustom || customVisible) return;
        try {
            if (customOverlay == null) customOverlay = new CustomAodOverlay(this);
            if (!customOverlay.show()) {
                completedThisSleepCycle = true;
                updateNotification("Custom AOD could not start on lock screen");
                return;
            }
            usingOverlay = true;
            customVisible = true;
            wakeDisplay();
            updateNotification("Custom AOD overlay fallback showing");
            if (AppPrefs.getCustomAodMode(this) == AppPrefs.CUSTOM_AOD_TEMPORARY) {
                main.postDelayed(this::finishTemporaryOverlay,
                        AppPrefs.getCustomAodSeconds(this) * 1000L);
            }
        } catch (Throwable t) {
            completedThisSleepCycle = true;
            updateNotification("Custom AOD could not start");
        }
    }

    private void finishTemporaryOverlay() {
        if (!customVisible || completedThisSleepCycle) return;
        completedThisSleepCycle = true;
        endingCustom = true;
        hideCustomAod();
        requestSleep(this);
        main.postDelayed(() -> endingCustom = false, 1200L);
    }

    private void hideCustomAod() {
        if (customOverlay != null) customOverlay.hide();
        CustomAodActivity.finishVisible();
        usingOverlay = false;
        customVisible = false;
        launchInProgress = false;
    }

    @SuppressWarnings("deprecation")
    private void wakeDisplay() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        PowerManager.WakeLock wakeLock = null;
        try {
            wakeLock = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "AODControl:custom-aod-wake");
            wakeLock.acquire(1200L);
        } catch (Throwable ignored) {
            if (wakeLock != null && wakeLock.isHeld()) {
                try { wakeLock.release(); } catch (Throwable ignored2) {}
            }
        }
    }

    static void markCustomVisible(boolean visible) {
        customVisible = visible;
        if (visible) launchInProgress = false;
    }

    static void markTemporaryComplete(Context context) {
        completedThisSleepCycle = true;
        endingCustom = true;
        customVisible = false;
        requestSleep(context);
    }

    static void markUserDismissed() {
        completedThisSleepCycle = true;
        customVisible = false;
    }

    static void requestSleep(Context context) {
        new Thread(() -> {
            try { Thread.sleep(120L); } catch (InterruptedException ignored) {}
            IAodShellService shell = ShizukuBridge.getService();
            if (shell != null) {
                try { shell.sleepScreen(); } catch (Throwable ignored) {}
            }
        }, "AODControl-sleep").start();
    }

    public static boolean isRunning() { return running; }

    public static boolean hasOverlayPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    private static boolean shouldServiceRun(Context context) {
        return AppPrefs.canRunUniversalAod(context) && hasOverlayPermission(context);
    }

    public static void sync(Context context) {
        Context app = context.getApplicationContext();
        if (shouldServiceRun(app)) {
            Intent intent = new Intent(app, UniversalAodService.class).setAction(ACTION_REFRESH);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent);
                else app.startService(intent);
            } catch (Throwable ignored) {}
        } else {
            try { app.stopService(new Intent(app, UniversalAodService.class)); } catch (Throwable ignored) {}
        }
    }

    public static String statusText(Context context) {
        if (!AppPrefs.isUniversalAodEnabled(context)) return "Disabled";
        if (!hasOverlayPermission(context)) return "Needs Display over other apps permission";
        int tech = AppPrefs.getResolvedDisplayTechnology(context);
        if (tech != AppPrefs.DISPLAY_OLED && !AppPrefs.isLcdAodAllowed(context)) {
            return tech == AppPrefs.DISPLAY_LCD ? "LCD AOD is blocked for safety" : "Display type unknown • LCD permission required";
        }
        if (tech == AppPrefs.DISPLAY_OLED) return "Ready • waits for native AOD to turn off";
        return "Ready • LCD custom AOD allowed";
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Universal AOD", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Runs only while Universal AOD fallback is enabled");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, UniversalAodSettingsActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("AODControl • Universal AOD")
                .setContentText(text)
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
}
