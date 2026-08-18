package com.huraiz.aodcontrol;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

import rikka.shizuku.Shizuku;

public final class ShizukuBridge {
    public interface Listener {
        void onShizukuStateChanged();
    }

    public static final int REQUEST_CODE = 4017;
    private static final int USER_SERVICE_VERSION = 2;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static Context appContext;
    private static volatile IAodShellService service;
    private static volatile boolean binding;
    private static Shizuku.UserServiceArgs userServiceArgs;
    private static boolean initialized;

    private ShizukuBridge() {}

    public static synchronized void init(Context context) {
        if (initialized) return;
        initialized = true;
        appContext = context.getApplicationContext();

        Shizuku.addBinderReceivedListenerSticky(BINDER_RECEIVED);
        Shizuku.addBinderDeadListener(BINDER_DEAD);
        Shizuku.addRequestPermissionResultListener(PERMISSION_RESULT);
        bindIfPossible();
    }

    public static void addListener(Listener listener) {
        if (listener == null) return;
        LISTENERS.addIfAbsent(listener);
        MAIN.post(listener::onShizukuStateChanged);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static IAodShellService getService() {
        IAodShellService current = service;
        if (current == null) bindIfPossible();
        return current;
    }

    public static boolean isBinderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isSupportedVersion() {
        try {
            return isBinderAlive() && !Shizuku.isPreV11();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hasPermission() {
        if (!isSupportedVersion()) return false;
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isReady() {
        return isBinderAlive() && hasPermission() && service != null;
    }

    public static boolean isBinding() {
        return binding;
    }

    public static void requestPermission(Activity activity) {
        if (activity == null) return;
        if (!isBinderAlive()) {
            notifyListeners();
            return;
        }
        try {
            if (Shizuku.isPreV11()) {
                notifyListeners();
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindIfPossible();
                return;
            }
            if (!Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(REQUEST_CODE);
            }
        } catch (Throwable ignored) {
            notifyListeners();
        }
    }

    public static synchronized void bindIfPossible() {
        if (appContext == null || service != null || binding) return;
        if (!isSupportedVersion() || !hasPermission()) return;

        try {
            if (userServiceArgs == null) {
                ComponentName component = new ComponentName(
                        appContext.getPackageName(), AodShellUserService.class.getName());
                userServiceArgs = new Shizuku.UserServiceArgs(component)
                        .daemon(false)
                        .processNameSuffix("aod_shell")
                        .debuggable(false)
                        .version(USER_SERVICE_VERSION);
            }
            binding = true;
            Shizuku.bindUserService(userServiceArgs, CONNECTION);
            notifyListeners();
        } catch (Throwable ignored) {
            binding = false;
            service = null;
            notifyListeners();
        }
    }

    private static final Shizuku.OnBinderReceivedListener BINDER_RECEIVED = () -> {
        bindIfPossible();
        notifyListeners();
    };

    private static final Shizuku.OnBinderDeadListener BINDER_DEAD = () -> {
        service = null;
        binding = false;
        notifyListeners();
    };

    private static final Shizuku.OnRequestPermissionResultListener PERMISSION_RESULT = (requestCode, grantResult) -> {
        if (requestCode != REQUEST_CODE) return;
        if (grantResult == PackageManager.PERMISSION_GRANTED) bindIfPossible();
        notifyListeners();
    };

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            binding = false;
            if (binder != null && binder.pingBinder()) {
                service = IAodShellService.Stub.asInterface(binder);
            } else {
                service = null;
            }
            notifyListeners();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binding = false;
            service = null;
            notifyListeners();
        }
    };

    private static void notifyListeners() {
        MAIN.post(() -> {
            for (Listener listener : LISTENERS) {
                try {
                    listener.onShizukuStateChanged();
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
