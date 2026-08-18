package com.huraiz.aodcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && AppPrefs.getMode(context) == AppPrefs.MODE_SMART) {
            try {
                SmartDimmingService.start(context);
            } catch (Throwable ignored) {
                // If the OS blocks a foreground-service launch at boot, opening
                // AOD Control will resume it. Manual modes never start a service.
            }
        }
    }
}
