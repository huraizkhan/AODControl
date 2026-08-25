package com.huraiz.aodcontrol;

import android.content.Context;
import android.media.AudioManager;
import android.util.DisplayMetrics;

final class VolumeSlideController {
    private static final float DEAD_ZONE_INCHES = 0.055f;

    private VolumeSlideController() {}

    static void apply(Context context, float verticalFractionUp) {
        if (context == null || !Float.isFinite(verticalFractionUp)) return;
        if (Math.abs(verticalFractionUp) < 0.001f) return;

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        float yDpi = dm.ydpi;

        if (!Float.isFinite(yDpi) || yDpi < 100f || yDpi > 1000f) {
            yDpi = dm.densityDpi > 0 ? dm.densityDpi : 420f;
        }

        float heightPx = Math.max(1, dm.heightPixels);
        float inches = verticalFractionUp * heightPx / yDpi;

        if (Math.abs(inches) < DEAD_ZONE_INCHES) return;

        int percentPerInch = AppPrefs.getVolumeSliderSensitivity(context);
        float requestedPercent = inches * percentPerInch;

        AudioManager audio =
                (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        if (audio == null) return;

        try {
            int max = Math.max(
                    1,
                    audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            );

            int current =
                    audio.getStreamVolume(AudioManager.STREAM_MUSIC);

            int delta =
                    Math.round((requestedPercent / 100f) * max);

            if (delta == 0)
                delta = requestedPercent > 0f ? 1 : -1;

            int target =
                    Math.max(0, Math.min(max, current + delta));

            if (target != current)
                audio.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        target,
                        0
                );

        } catch (Throwable ignored) {}
    }
}
