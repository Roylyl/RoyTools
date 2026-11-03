package com.github.kr328.clash.service.util;

/** Pure counter state, shared by the Android store and its isolated regression checks. */
public final class ProxyUsageLedger {
    public final String day;
    public final String session;
    public final long wifi;
    public final long mobile;
    public final long lastNativeWiFi;
    public final long lastNativeMobile;

    public ProxyUsageLedger(String day, String session, long wifi, long mobile, long lastNativeWiFi,
                            long lastNativeMobile) {
        this.day = day;
        this.session = session;
        this.wifi = Math.max(0L, wifi);
        this.mobile = Math.max(0L, mobile);
        this.lastNativeWiFi = Math.max(0L, lastNativeWiFi);
        this.lastNativeMobile = Math.max(0L, lastNativeMobile);
    }

    public ProxyUsageLedger forDay(String localDay) {
        return day.equals(localDay) ? this : new ProxyUsageLedger(localDay, "", 0L, 0L, 0L, 0L);
    }

    public ProxyUsageLedger sample(String nativeDay, String processSession, long nativeWiFi, long nativeMobile) {
        final ProxyUsageLedger bucket = forDay(nativeDay);
        final boolean sameProcess = processSession.equals(bucket.session);
        final long previousWiFi = sameProcess ? bucket.lastNativeWiFi : 0L;
        final long previousMobile = sameProcess ? bucket.lastNativeMobile : 0L;
        // Native counters are monotonic for this process and local day, including profile reloads.
        // Ignore a regressed observation instead of later counting old bytes twice.
        final long nextWiFi = Math.max(previousWiFi, Math.max(0L, nativeWiFi));
        final long nextMobile = Math.max(previousMobile, Math.max(0L, nativeMobile));
        return new ProxyUsageLedger(nativeDay, processSession,
                add(bucket.wifi, nextWiFi - previousWiFi),
                add(bucket.mobile, nextMobile - previousMobile), nextWiFi, nextMobile);
    }

    public boolean sameState(ProxyUsageLedger other) {
        return day.equals(other.day) && session.equals(other.session) && wifi == other.wifi && mobile == other.mobile
                && lastNativeWiFi == other.lastNativeWiFi
                && lastNativeMobile == other.lastNativeMobile;
    }

    private static long add(long total, long delta) {
        return total > Long.MAX_VALUE - delta ? Long.MAX_VALUE : total + delta;
    }
}
