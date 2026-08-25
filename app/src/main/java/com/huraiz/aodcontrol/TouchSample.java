package com.huraiz.aodcontrol;

final class TouchSample {
    final int startX;
    final int startY;
    final int endX;
    final int endY;
    final int maxX;
    final int maxY;
    final long durationMs;

    private TouchSample(int startX, int startY, int endX, int endY,
                        int maxX, int maxY, long durationMs) {
        this.startX = startX;
        this.startY = startY;
        this.endX = endX;
        this.endY = endY;
        this.maxX = Math.max(1, maxX);
        this.maxY = Math.max(1, maxY);
        this.durationMs = Math.max(1L, durationMs);
    }

    static TouchSample parse(String payload) {
        if (payload == null || payload.isEmpty() || payload.startsWith("ERR:")) return null;
        String[] p = payload.split(",");
        if (p.length != 7) return null;
        try {
            return new TouchSample(
                    Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                    Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                    Integer.parseInt(p[4]), Integer.parseInt(p[5]),
                    Long.parseLong(p[6]));
        } catch (Throwable ignored) {
            return null;
        }
    }

    boolean startsInsideActiveHeight(int activeHeightPercent) {
        int pct = clamp(activeHeightPercent, 40, 100);
        float margin = maxY * ((100f - pct) / 200f);
        return startY >= margin && startY <= (maxY - margin);
    }

    boolean isTap(int sensitivityPercent) {
        float s = clamp(sensitivityPercent, 1, 100) / 100f;
        float movement = 0.04f + 0.043f * s;
        long maxDuration = Math.round(350f + 143f * s);
        return durationMs <= maxDuration
                && Math.abs(endX - startX) <= maxX * movement
                && Math.abs(endY - startY) <= maxY * movement;
    }

    boolean closeTo(TouchSample other) {
        if (other == null) return false;
        return Math.abs(endX - other.endX) <= maxX * 0.16f
                && Math.abs(endY - other.endY) <= maxY * 0.16f;
    }

    String movementGesture(int edgeWidthPercent, int sensitivityPercent) {
        float dx = endX - startX;
        float dy = endY - startY;
        float ax = Math.abs(dx);
        float ay = Math.abs(dy);

        float s = clamp(sensitivityPercent, 1, 100) / 100f;
        float edgeFraction = clamp(edgeWidthPercent, 8, 35) / 100f;

        // Higher sensitivity accepts shorter and less perfectly straight swipes.
        // At the default 70%, these thresholds match v1.4.1 closely.
        float edgeThreshold = 0.15f - (0.10f * s);
        float normalThreshold = 0.26f - (0.20f * s);
        float dominance = 1.45f - (0.50f * s);

        boolean edgeVertical = ay >= maxY * edgeThreshold && ay > ax * dominance;
        if (edgeVertical && startX <= maxX * edgeFraction) {
            return dy < 0 ? AppPrefs.GESTURE_LEFT_EDGE_UP : AppPrefs.GESTURE_LEFT_EDGE_DOWN;
        }
        if (edgeVertical && startX >= maxX * (1f - edgeFraction)) {
            return dy < 0 ? AppPrefs.GESTURE_RIGHT_EDGE_UP : AppPrefs.GESTURE_RIGHT_EDGE_DOWN;
        }

        if (ax >= maxX * normalThreshold && ax > ay * dominance) {
            return dx > 0 ? AppPrefs.GESTURE_SWIPE_LEFT_TO_RIGHT
                    : AppPrefs.GESTURE_SWIPE_RIGHT_TO_LEFT;
        }
        if (ay >= maxY * normalThreshold && ay > ax * dominance) {
            return dy < 0 ? AppPrefs.GESTURE_SWIPE_UP : AppPrefs.GESTURE_SWIPE_DOWN;
        }
        return null;
    }

    float verticalFractionUp() {
        return (startY - endY) / (float) maxY;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
