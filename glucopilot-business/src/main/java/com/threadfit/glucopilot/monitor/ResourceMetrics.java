package com.threadfit.glucopilot.monitor;

import java.util.List;

/** Byte counts are host metrics; container metrics retain Docker's separate accounting. */
public final class ResourceMetrics {
    private ResourceMetrics() {}

    public record Response(boolean available, boolean stale, String message, Snapshot snapshot) {}
    public record Snapshot(int schemaVersion, String sampledAt, Host host, boolean containersAvailable,
                           List<Container> containers, List<Sample> history) {}
    public record Host(Double cpuPercent, int cpuCores, long memoryTotalBytes, long memoryAvailableBytes,
                       long memoryUsedBytes, double memoryPercent, long swapTotalBytes, long swapUsedBytes,
                       long diskTotalBytes, long diskUsedBytes, long diskFreeBytes, double diskPercent,
                       long uptimeSeconds) {}
    public record Container(String name, String service, String state, Double cpuPercent,
                            Long memoryUsedBytes, Long memoryLimitBytes) {}
    public record Sample(String sampledAt, Double cpuPercent, double memoryPercent) {}
}
