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
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.Calendar;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AutomationService extends Service implements ShizukuBridge.Listener {
    private static volatile boolean running;
    public static final String ACTION_REFRESH = "com.huraiz.aodcontrol.action.REFRESH_AUTOMATION";
    private static final String CHANNEL_ID = "aod_active";
    private static final int NOTIFICATION_ID = 1030;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean evaluating;
    private volatile boolean reevaluateRequested;
    private volatile boolean forceNextApply;
    private volatile String lastAppliedKey = "";
    private volatile boolean lastApplyOk;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            requestEvaluation(false);
            long next = AppPrefs.isModeEnabled(AutomationService.this, AppPrefs.MODE_NAVIGATION)
                    ? 4000L : 30000L;
            main.postDelayed(this, next);
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // Power broadcasts must be handled immediately. Do not clear the old
            // key here: keeping it lets us detect the actual mode transition and
            // refresh the already-running native AOD only when state changed.
            requestEvaluation(false);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        ShizukuBridge.addListener(this);
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Starting automation…"));

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }

        main.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!AppPrefs.anyAutomationEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            requestEvaluation(true);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        try { unregisterReceiver(stateReceiver); } catch (Throwable ignored) {}
        ShizukuBridge.removeListener(this);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onShizukuStateChanged() {
        if (ShizukuBridge.isReady()) {
            requestEvaluation(true);
        } else {
            updateNotification("Waiting for Shizuku");
        }
    }

    private synchronized void requestEvaluation(boolean forceApply) {
        if (!AppPrefs.anyAutomationEnabled(this)) {
            stopSelf();
            return;
        }
        if (forceApply) forceNextApply = true;
        if (evaluating) {
            reevaluateRequested = true;
            return;
        }

        evaluating = true;
        final boolean forceThisPass = forceNextApply;
        forceNextApply = false;
        io.execute(() -> evaluateAndApplyNow(forceThisPass));
    }

    private void evaluateAndApplyNow(boolean forceApply) {
        try {
            EffectiveState state = resolveState();
            String key = state.reason + "|" + state.behavior.type + "|" + state.behavior.opacity;
            boolean changed = !key.equals(lastAppliedKey);
            if (forceApply || changed || !lastApplyOk) {
                if (!ShizukuBridge.isReady()) {
                    lastApplyOk = false;
                    AppPrefs.saveLastState(this, state.reason, state.behavior, false);
                    updateNotification("Waiting for Shizuku");
                } else {
                    AodApplier.Result result = AodApplier.apply(this, state.behavior);
                    lastApplyOk = result.ok;
                    if (result.ok) {
                        lastAppliedKey = key;
                        // A Pixel/SystemUI doze session may keep the old scrim even
                        // after settings changed. Refresh it after any real automatic
                        // transition (especially charger connect/disconnect).
                        if (changed || forceApply) refreshNativeAodWhenAmbient();
                    }
                    AppPrefs.saveLastState(this, state.reason, state.behavior, result.ok);
                    updateNotification(state.reason + " • " + AppPrefs.describeBehavior(state.behavior));
                }
            }
        } finally {
            boolean again;
            synchronized (this) {
                evaluating = false;
                again = reevaluateRequested || forceNextApply;
                reevaluateRequested = false;
            }
            if (again) main.post(() -> requestEvaluation(false));
        }
    }

    private void refreshNativeAodWhenAmbient() {
        IAodShellService shell = ShizukuBridge.getService();
        if (shell == null) return;

        // Plugging a charger can briefly make the display interactive. Wait for
        // that pulse to settle, then restart only the native doze session. This
        // avoids a full KEYCODE_WAKEUP/lock-screen flash.
        for (int attempt = 0; attempt < 5; attempt++) {
            if (!isScreenInteractive()) {
                try { shell.refreshNativeAod(); } catch (Throwable ignored) {}
                return;
            }
            try { Thread.sleep(attempt == 0 ? 180L : 350L); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean isScreenInteractive() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    private EffectiveState resolveState() {
        // Fixed priority: Charging > Navigation > Outdoor > Night > Default.
        if (AppPrefs.isModeEnabled(this, AppPrefs.MODE_CHARGING) && isCharging()) {
            return new EffectiveState("Charging", AppPrefs.getModeBehaviorConfig(this, AppPrefs.MODE_CHARGING));
        }

        if (AppPrefs.isModeEnabled(this, AppPrefs.MODE_NAVIGATION)) {
            Set<String> packages = AppPrefs.getNavigationPackages(this);
            if (!packages.isEmpty()) {
                IAodShellService shell = ShizukuBridge.getService();
                if (shell != null) {
                    try {
                        String foreground = shell.getForegroundPackage();
                        if (foreground != null && packages.contains(foreground)) {
                            return new EffectiveState("Navigation", AppPrefs.getModeBehaviorConfig(this, AppPrefs.MODE_NAVIGATION));
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }

        int now = currentMinutes();
        if (AppPrefs.isModeEnabled(this, AppPrefs.MODE_OUTDOOR)
                && inRange(now,
                AppPrefs.getStartMinutes(this, AppPrefs.MODE_OUTDOOR),
                AppPrefs.getEndMinutes(this, AppPrefs.MODE_OUTDOOR))) {
            return new EffectiveState("Outdoor hours", AppPrefs.getModeBehaviorConfig(this, AppPrefs.MODE_OUTDOOR));
        }

        if (AppPrefs.isModeEnabled(this, AppPrefs.MODE_NIGHT)
                && inRange(now,
                AppPrefs.getStartMinutes(this, AppPrefs.MODE_NIGHT),
                AppPrefs.getEndMinutes(this, AppPrefs.MODE_NIGHT))) {
            return new EffectiveState("Night mode", AppPrefs.getModeBehaviorConfig(this, AppPrefs.MODE_NIGHT));
        }

        return new EffectiveState("Default", AppPrefs.getDefaultBehaviorConfig(this));
    }

    private boolean isCharging() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return false;
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        return plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    private static int currentMinutes() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    static boolean inRange(int now, int start, int end) {
        if (start == end) return true;
        if (start < end) return now >= start && now < end;
        return now >= start || now < end;
    }

    public static boolean isRunning() {
        return running;
    }

    public static void sync(Context context) {
        Context app = context.getApplicationContext();
        if (!AppPrefs.isForegroundFallbackEnabled(app)) {
            try { app.stopService(new Intent(app, AutomationService.class)); } catch (Throwable ignored) {}
            ShizukuBackgroundEngine.sync(app);
            return;
        }
        ShizukuBackgroundEngine.stop();
        if (AppPrefs.anyAutomationEnabled(app)) {
            Intent intent = new Intent(app, AutomationService.class);
            intent.setAction(ACTION_REFRESH);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent);
                } else {
                    app.startService(intent);
                }
            } catch (Throwable ignored) {}
        } else {
            try { app.stopService(new Intent(app, AutomationService.class)); } catch (Throwable ignored) {}
        }
    }

    public static void refresh(Context context) {
        sync(context);
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

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("AODControl active")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(content)
                .build();
    }

    private void updateNotification(String text) {
        main.post(() -> {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
        });
    }

    private static final class EffectiveState {
        final String reason;
        final AppPrefs.Behavior behavior;

        EffectiveState(String reason, AppPrefs.Behavior behavior) {
            this.reason = reason;
            this.behavior = behavior;
        }
    }
}
