package com.huraiz.aodcontrol;

interface IAodShellService {
    String getSetting(String namespace, String key) = 1;
    String putSetting(String namespace, String key, String value) = 2;
    String deleteSetting(String namespace, String key) = 3;
    String setUniformDimming(int raw, int bucketCount) = 4;
    String removeDimmingOverride() = 5;
    int[] getSystemDefaultDimmingArray() = 6;
    String getForegroundPackage() = 7;
    String getDisplayPanelHints() = 8;
    String sleepScreen() = 9;
    String startCustomAod() = 10;

    void destroy() = 16777114;
}
