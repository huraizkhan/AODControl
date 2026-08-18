package com.huraiz.aodcontrol;

public final class DimmingProfile {
    public int visibleSeconds;
    public int delaySeconds;
    public boolean autoOpacity;
    public int startOpacity;
    public int endOpacity;

    public DimmingProfile(int visibleSeconds, int delaySeconds, boolean autoOpacity,
                          int startOpacity, int endOpacity) {
        this.visibleSeconds = clamp(visibleSeconds, 1, 60);
        this.delaySeconds = clamp(delaySeconds, 1, this.visibleSeconds);
        this.autoOpacity = autoOpacity;
        this.startOpacity = clamp(startOpacity, 0, 255);
        this.endOpacity = clamp(endOpacity, 0, 255);
    }

    public int finalOpacity() {
        return autoOpacity ? 255 : endOpacity;
    }

    public DimmingProfile copy() {
        return new DimmingProfile(visibleSeconds, delaySeconds, autoOpacity, startOpacity, endOpacity);
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
