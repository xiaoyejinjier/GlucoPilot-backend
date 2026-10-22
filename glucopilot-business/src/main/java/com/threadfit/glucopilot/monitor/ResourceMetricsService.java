package com.threadfit.glucopilot.monitor;

import com.threadfit.glucopilot.common.util.JsonUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class ResourceMetricsService {
    private final Path file;
    private final JsonUtils json;
    private final Clock clock;

    @Autowired
    public ResourceMetricsService(JsonUtils json,
            @Value("${monitor.metrics.path:${SERVER_METRICS_PATH:/app/monitor/metrics.json}}") String file) {
        this(json, Path.of(file), Clock.systemUTC());
    }

    ResourceMetricsService(JsonUtils json, Path file, Clock clock) {
        this.json = json;
        this.file = file;
        this.clock = clock;
    }

    public ResourceMetrics.Response read() {
        try {
            if (!Files.isRegularFile(file)) return missing("尚未采集服务器资源，请检查采集器和只读挂载。");
            if (Files.size(file) > 1024 * 1024) return missing("资源快照无效，请检查采集器。");
            var snapshot = json.fromJson(Files.readString(file), ResourceMetrics.Snapshot.class);
            if (!valid(snapshot)) return missing("资源快照无效，请检查采集器。");
            long age = Duration.between(Instant.parse(snapshot.sampledAt()), clock.instant()).getSeconds();
            boolean stale = age > 30 || age < -5;
            String message = stale ? "采样已过期或服务器时间异常，下方展示最后一次采样。"
                    : (!snapshot.containersAvailable() ? "宿主机数据正常，但容器资源暂未采集成功。" : "采样正常");
            return new ResourceMetrics.Response(true, stale, message, snapshot);
        } catch (Exception ex) {
            // Never return parser errors, paths, or raw file contents to clients.
            return missing("无法读取资源快照，请检查采集器。");
        }
    }

    private ResourceMetrics.Response missing(String message) {
        return new ResourceMetrics.Response(false, true, message, null);
    }

    private boolean valid(ResourceMetrics.Snapshot s) {
        if (s == null || s.schemaVersion() != 1 || s.host() == null || s.history() == null
                || s.containers() == null || s.history().size() > 60 || s.containers().size() > 30) return false;
        Instant sampled = Instant.parse(s.sampledAt());
        var h = s.host();
        if (h.cpuCores() <= 0 || !percent(h.cpuPercent()) || !percent(h.memoryPercent())
                || !percent(h.diskPercent()) || h.uptimeSeconds() < 0
                || h.memoryTotalBytes() <= 0 || h.memoryAvailableBytes() < 0
                || h.memoryAvailableBytes() > h.memoryTotalBytes()
                || h.memoryUsedBytes() != h.memoryTotalBytes() - h.memoryAvailableBytes()
                || h.swapTotalBytes() < 0 || h.swapUsedBytes() < 0 || h.swapUsedBytes() > h.swapTotalBytes()
                || h.diskTotalBytes() <= 0 || h.diskUsedBytes() < 0 || h.diskFreeBytes() < 0
                || h.diskUsedBytes() > h.diskTotalBytes() || h.diskFreeBytes() > h.diskTotalBytes()) return false;
        Instant previous = null;
        for (var row : s.history()) {
            if (row == null || !percent(row.cpuPercent()) || !percent(row.memoryPercent())) return false;
            Instant at = Instant.parse(row.sampledAt());
            if (at.isAfter(sampled) || (previous != null && !at.isAfter(previous))) return false;
            previous = at;
        }
        for (var row : s.containers()) {
            if (row == null || row.name() == null || row.name().length() > 160 || row.state() == null
                    || (row.cpuPercent() != null && (!Double.isFinite(row.cpuPercent()) || row.cpuPercent() < 0))
                    || (row.memoryUsedBytes() != null && row.memoryUsedBytes() < 0)
                    || (row.memoryLimitBytes() != null && row.memoryLimitBytes() <= 0)) return false;
        }
        return true;
    }

    private boolean percent(Double value) {
        return value == null || (Double.isFinite(value) && value >= 0 && value <= 100);
    }
}
