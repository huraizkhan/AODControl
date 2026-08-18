package com.huraiz.aodcontrol;

import com.huraiz.aodcontrol.IFingerprintTouchCallback;

interface IAodShellService {
    String getSetting(String namespace, String key) = 1;
    String putSetting(String namespace, String key, String value) = 2;
    String deleteSetting(String namespace, String key) = 3;
    String setUniformDimming(int raw, int bucketCount) = 4;
    String setDimmingArray(in int[] values) = 5;
    String removeDimmingOverride() = 6;
    int[] getSystemDefaultDimmingArray() = 7;
    String getTouchCapabilities() = 8;
    int startTouchMonitor(float centerXNorm, float centerYNorm, float radiusXNorm, float radiusYNorm,
                          IFingerprintTouchCallback callback) = 9;
    void stopTouchMonitor() = 10;

    // Reserved transaction used by Shizuku to tear down a UserService cleanly.
    void destroy() = 16777114;
}
