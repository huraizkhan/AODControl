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

    boolean isTap() {
        return durationMs <= 450L
                && Math.abs(endX - startX) <= maxX * 0.07f
                && Math.abs(endY - startY) <= maxY * 0.07f;
    }

    boolean closeTo(TouchSample other) {
        if (other == null) return false;
        return Math.abs(endX - other.endX) <= maxX * 0.16f
                && Math.abs(endY - other.endY) <= maxY * 0.16f;
    }

    String movementGesture() {
        float dx = endX - startX;
        float dy = endY - startY;
        float ax = Math.abs(dx);
        float ay = Math.abs(dy);

        // Edge zones intentionally occupy 20% of either side so they are easy to
        // hit on dim native-AOD screens. Direction is part of the gesture key.
        boolean edgeVertical = ay >= maxY * 0.08f && ay > ax * 1.15f;
        if (edgeVertical && startX <= maxX * 0.20f) {
            return dy < 0 ? AppPrefs.GESTURE_LEFT_EDGE_UP : AppPrefs.GESTURE_LEFT_EDGE_DOWN;
        }
        if (edgeVertical && startX >= maxX * 0.80f) {
            return dy < 0 ? AppPrefs.GESTURE_RIGHT_EDGE_UP : AppPrefs.GESTURE_RIGHT_EDGE_DOWN;
        }

        // Horizontal swipes are deliberately more forgiving than v1.4.0. This
        // helps track-change gestures register even when the finger path is not
        // perfectly straight on AOD.
        if (ax >= maxX * 0.12f && ax > ay * 1.10f) {
            return dx > 0 ? AppPrefs.GESTURE_SWIPE_LEFT_TO_RIGHT
                    : AppPrefs.GESTURE_SWIPE_RIGHT_TO_LEFT;
        }
        if (ay >= maxY * 0.12f && ay > ax * 1.10f) {
            return dy < 0 ? AppPrefs.GESTURE_SWIPE_UP : AppPrefs.GESTURE_SWIPE_DOWN;
        }
        return null;
    }

    float verticalFractionUp() {
        return (startY - endY) / (float) maxY;
    }
}
