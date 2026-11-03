package com.mahmutalperenunal.adaptivehz.core.shizuku;

import android.os.IBinder;
import com.mahmutalperenunal.adaptivehz.core.shizuku.IInputEventCallback;

interface IInputMonitorService {
    String listInputDevices() = 0;
    String readRefreshRateSetting(String key) = 1;
    boolean writeRefreshRateSetting(String key, String value) = 2;
    boolean deleteRefreshRateSetting(String key) = 3;
    int readSamsungRefreshRateMode() = 4;
    boolean writeSamsungRefreshRateMode(int value) = 5;
    String inspectSamsungRefreshRateTokenApi() = 6;
    boolean applySamsungRefreshRateTokenLimits(int minRefreshRate, int maxRefreshRate) = 7;
    boolean releaseSamsungRefreshRateTokenLimits() = 8;
    boolean applyHyperOsRefreshRateSetting(String key, int refreshRate) = 9;
    boolean releaseHyperOsRefreshRateSetting() = 10;
    void registerDisplaySessionClient(IBinder client) = 11;
    void startMonitoring(String devicePath, IInputEventCallback callback) = 12;
    void stopMonitoring() = 13;
    void destroy() = 16777114;
}

