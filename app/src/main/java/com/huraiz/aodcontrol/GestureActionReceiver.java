package com.huraiz.aodcontrol;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;

/** Quick app-side actions requested by the persistent Shizuku gesture engine. */
public final class GestureActionReceiver extends BroadcastReceiver {
    private static volatile boolean torchOn;

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !"com.huraiz.aodcontrol.GESTURE_ACTION".equals(intent.getAction())) return;
        int action = intent.getIntExtra("action", AppPrefs.GESTURE_ACTION_NONE);
        Context app = context.getApplicationContext();
        if (action == AppPrefs.GESTURE_ACTION_TORCH) {
            toggleTorch(app);
        } else if (action == AppPrefs.GESTURE_ACTION_VOLUME_SLIDER) {
            int normalized = intent.getIntExtra("value", 0);
            VolumeSlideController.apply(app, normalized / 10000f);
        }
    }

    private static void toggleTorch(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) return;
        try {
            for (String id : manager.getCameraIdList()) {
                Boolean flash = manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) {
                    torchOn = !torchOn;
                    manager.setTorchMode(id, torchOn);
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }
}
