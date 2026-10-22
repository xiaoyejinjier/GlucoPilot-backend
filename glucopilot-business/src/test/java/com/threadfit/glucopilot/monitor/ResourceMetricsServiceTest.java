package com.threadfit.glucopilot.monitor;

import com.threadfit.glucopilot.common.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.access.prepost.PreAuthorize;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ResourceMetricsServiceTest {
    @TempDir Path temp;
    private final JsonUtils json = new JsonUtils(new ObjectMapper());
    private final Instant now = Instant.parse("2026-10-22T01:00:00Z");

    private ResourceMetricsService service() {
        return new ResourceMetricsService(json, temp.resolve("metrics.json"), Clock.fixed(now, ZoneOffset.UTC));
    }
    private ResourceMetrics.Snapshot snapshot(String at) {
        var host = new ResourceMetrics.Host(null, 2, 1000, 400, 600, 60, 0, 0, 2000, 800, 1200, 40, 123);
        return new ResourceMetrics.Snapshot(1, at, host, false, List.of(),
                List.of(new ResourceMetrics.Sample(at, null, 60)));
    }
    private void write(ResourceMetrics.Snapshot value) throws Exception {
        Files.writeString(temp.resolve("metrics.json"), json.toJson(value));
    }
    @Test void missingFileDoesNotInventJvmOrHostData() {
        var response = service().read();
        assertFalse(response.available());
        assertNull(response.snapshot());
    }
    @Test void malformedAndOversizedFilesFailClosed() throws Exception {
        Files.writeString(temp.resolve("metrics.json"), "{not-json");
        assertFalse(service().read().available());
        Files.writeString(temp.resolve("metrics.json"), " ".repeat(1024 * 1024 + 1));
        assertFalse(service().read().available());
    }
    @Test void freshHostMetricsRemainAvailableWhenDockerIsUnavailable() throws Exception {
        write(snapshot(now.toString()));
        var response = service().read();
        assertTrue(response.available());
        assertFalse(response.stale());
        assertFalse(response.snapshot().containersAvailable());
        assertNull(response.snapshot().host().cpuPercent());
        assertEquals(400, response.snapshot().host().memoryAvailableBytes());
    }
    @Test void staleAndFutureSamplesAreMarkedWithoutChangingTheirTimestamp() throws Exception {
        for (long seconds : new long[]{-31, 6}) {
            String at = now.plusSeconds(seconds).toString();
            write(snapshot(at));
            var response = service().read();
            assertTrue(response.stale());
            assertEquals(at, response.snapshot().sampledAt());
        }
    }
    @Test void duplicateHistoryAndUnsupportedSchemaAreRejected() throws Exception {
        var s = snapshot(now.toString());
        write(new ResourceMetrics.Snapshot(1, s.sampledAt(), s.host(), true, List.of(),
                List.of(s.history().getFirst(), s.history().getFirst())));
        assertFalse(service().read().available());
        write(new ResourceMetrics.Snapshot(2, s.sampledAt(), s.host(), true, List.of(), List.of()));
        assertFalse(service().read().available());
    }
    @Test void endpointReusesServerPermission() throws Exception {
        var annotation = ResourceMonitorController.class.getMethod("resources").getAnnotation(PreAuthorize.class);
        assertEquals("@ss.hasPermi('monitor:server:list')", annotation.value());
    }
}
