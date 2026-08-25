package com.huraiz.aodcontrol;

interface IAodShellService {
    String getSetting(String namespace, String key) = 1;
    String putSetting(String namespace, String key, String value) = 2;
    String deleteSetting(String namespace, String key) = 3;
    String setUniformDimming(int raw, int bucketCount) = 4;
    String removeDimmingOverride() = 5;
    int[] getSystemDefaultDimmingArray() = 6;
    String getForegroundPackage() = 7;
    String getTouchCapabilities() = 8;
    String waitForTouchEvent(int timeoutMs) = 9;
    String wakeScreen() = 10;
    String dispatchMediaKey(int keyCode) = 11;
    String refreshNativeAod() = 12;
    String configureGestureEngine(boolean enabled, int activeHeight, int edgeWidth, int sensitivity, in int[] actions) = 13;
    String configureAutomationEngine(boolean enabled,
            int defaultBehavior, int defaultOpacity,
            boolean chargingEnabled, int chargingBehavior, int chargingOpacity,
            boolean navigationEnabled, int navigationBehavior, int navigationOpacity, in String[] navigationPackages,
            boolean outdoorEnabled, int outdoorBehavior, int outdoorOpacity, int outdoorStart, int outdoorEnd,
            boolean nightEnabled, int nightBehavior, int nightOpacity, int nightStart, int nightEnd) = 14;
    String getBackgroundEngineStatus() = 15;
    String stopBackgroundEngine() = 16;

    void destroy() = 16777114;
}
