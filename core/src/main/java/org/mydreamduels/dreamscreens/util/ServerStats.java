package org.mydreamduels.dreamscreens.util;

import org.bukkit.Bukkit;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * TPS / MSPT / uptime. On Paper these come from the server; on Folia there
 * is no single server tick, so the numbers of the region the caller runs in
 * are read (that's what /tps shows there). NaN = not available.
 */
public final class ServerStats {

    private static volatile boolean foliaBroken;
    private static volatile Method currentRegion, getData, getHandle, getReport;

    private ServerStats() {
    }

    /** [tps, mspt] for the calling thread's region (Folia) or the server (Paper). */
    public static double[] now() {
        double[] folia = folia();
        if (folia != null) return folia;
        double tps = Double.NaN, mspt = Double.NaN;
        try {
            double[] t = Bukkit.getTPS();
            if (t != null && t.length > 0) tps = Math.min(20, t[0]);
        } catch (Throwable ignored) {
        }
        try {
            mspt = Bukkit.getAverageTickTime();
        } catch (Throwable ignored) {
        }
        return new double[]{tps, mspt};
    }

    private static double[] folia() {
        if (foliaBroken || !org.mydreamduels.dreamscreens.platform.PlatformSupport.isFolia()) return null;
        try {
            if (currentRegion == null) {
                currentRegion = Class.forName("io.papermc.paper.threadedregions.TickRegionScheduler").getMethod("getCurrentRegion");
            }
            Object region = currentRegion.invoke(null);
            if (region == null) return new double[]{Double.NaN, Double.NaN}; // global / async thread
            if (getData == null) getData = region.getClass().getMethod("getData");
            Object data = getData.invoke(region);
            if (getHandle == null) getHandle = data.getClass().getMethod("getRegionSchedulingHandle");
            Object handle = getHandle.invoke(data);
            if (getReport == null) getReport = handle.getClass().getMethod("getTickReport5s", long.class);
            Object report = getReport.invoke(handle, System.nanoTime());
            if (report == null) return new double[]{Double.NaN, Double.NaN};
            double tps = average(call(report, "tpsData"));
            double mspt = average(call(report, "timePerTickData")) / 1.0e6;
            return new double[]{Math.min(20, tps), mspt};
        } catch (Throwable t) {
            foliaBroken = true;
            return null;
        }
    }

    private static Object call(Object o, String m) throws Exception {
        Method method = o.getClass().getMethod(m);
        method.setAccessible(true);
        return method.invoke(o);
    }

    private static double average(Object data) throws Exception {
        Object seg = call(data, "segmentAll");
        return ((Number) call(seg, "average")).doubleValue();
    }

    public static String format(double v) {
        return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%.1f", v);
    }

    public static String uptime() {
        long ms = ManagementFactory.getRuntimeMXBean().getUptime();
        long m = ms / 60000, h = m / 60, d = h / 24;
        if (d > 0) return d + "d " + (h % 24) + "h";
        if (h > 0) return h + "h " + (m % 60) + "m";
        return m + "m";
    }
}
