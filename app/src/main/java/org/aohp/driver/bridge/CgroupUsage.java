package org.aohp.driver.bridge;

import org.json.JSONObject;

/**
 * Parsed {@code getUsage} JSON from {@code aohp-containerd} (cgroup v2).
 */
public final class CgroupUsage {
    public boolean cgroupEnabled;
    public String cgroupPath = "";
    public long memoryCurrent;
    public String memoryMax = "";
    public long memoryPeak;
    public long cpuUsageUsec;
    public int pidsCurrent;

    public static CgroupUsage fromJson(String json) {
        CgroupUsage u = new CgroupUsage();
        if (json == null || json.isEmpty()) return u;
        try {
            JSONObject o = new JSONObject(json);
            u.cgroupEnabled = o.optBoolean("cgroupEnabled", false);
            u.cgroupPath = o.optString("cgroupPath", "");
            u.memoryCurrent = o.optLong("memoryCurrent", 0);
            u.memoryMax = o.optString("memoryMax", "");
            u.memoryPeak = o.optLong("memoryPeak", 0);
            u.cpuUsageUsec = o.optLong("cpuUsageUsec", 0);
            u.pidsCurrent = o.optInt("pidsCurrent", 0);
        } catch (Exception ignored) {
        }
        return u;
    }

    public String formatShort() {
        if (!cgroupEnabled) {
            return "cgroup: off";
        }
        String memH = humanSize(memoryCurrent);
        String max = memoryMax.isEmpty() ? "?" : memoryMax;
        return "Mem " + memH + " / " + max + " | pids " + pidsCurrent;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KiB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / (1024 * 1024)) + " MiB";
        return (bytes / (1024L * 1024 * 1024)) + " GiB";
    }
}
