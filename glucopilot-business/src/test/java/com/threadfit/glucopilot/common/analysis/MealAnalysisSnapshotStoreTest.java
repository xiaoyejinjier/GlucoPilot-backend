package com.threadfit.glucopilot.common.analysis;

import com.threadfit.glucopilot.common.util.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 纯 Mockito：不连真实 Redis，覆盖 put/get/miss/TTL/写异常/读异常/JSON 往返。
 */
@ExtendWith(MockitoExtension.class)
class MealAnalysisSnapshotStoreTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private JsonUtils json;
    private MealAnalysisSnapshotStore store;

    @BeforeEach void setup() {
        json = new JsonUtils(JsonMapper.builder().build());
        store = new MealAnalysisSnapshotStore(redis, json);
    }

    @Test void putWritesJsonWithThirtyMinuteTtl() {
        when(redis.opsForValue()).thenReturn(valueOps);

        assertTrue(store.put(1L, "token123456", snapshot()));

        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(valueOps).set(
                eq("meal:analysis:snap:1:token123456"), payload.capture(), eq(Duration.ofMinutes(30)));
        assertTrue(payload.getValue().contains("米饭"));
        assertTrue(payload.getValue().contains("UNRESOLVED"));
    }

    @Test void putReturnsFalseWhenRedisFails() {
        when(redis.opsForValue()).thenReturn(valueOps);
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        assertFalse(store.put(1L, "token123456", snapshot()));
    }

    @Test void getReturnsEmptyWhenKeyMissing() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        assertTrue(store.get(1L, "token123456").isEmpty());
    }

    @Test void getReturnsEmptyWhenRedisFails() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));

        assertTrue(store.get(1L, "token123456").isEmpty());
    }

    @Test void getRoundTripsWrittenSnapshot() {
        var written = new AtomicReference<String>();
        when(redis.opsForValue()).thenReturn(valueOps);
        org.mockito.Mockito.doAnswer(invocation -> {
            written.set(invocation.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(valueOps.get(anyString())).thenAnswer(invocation -> written.get());

        assertTrue(store.put(1L, "token123456", snapshot()));
        var loaded = store.get(1L, "token123456").orElseThrow();

        assertEquals(1L, loaded.getUserId());
        assertEquals(8L, loaded.getMediaId());
        assertEquals("breakfast", loaded.getNodeCode());
        assertEquals("qwen3-vl-plus", loaded.getModel());
        assertEquals(1, loaded.getItems().size());
        assertEquals("米饭", loaded.getItems().get(0).getFoodName());
        assertEquals(new BigDecimal("83.2"), loaded.getItems().get(0).getGiValue());
        assertEquals("UNRESOLVED", loaded.getItems().get(0).getLibraryStatus());
        assertEquals(50, loaded.getItems().get(0).getConfidence());
    }

    @Test void maskTokenExposesOnlyPrefix() {
        assertEquals("abc123…", MealAnalysisSnapshotStore.maskToken("abc123def456"));
        assertEquals("***", MealAnalysisSnapshotStore.maskToken("abc"));
        assertEquals("***", MealAnalysisSnapshotStore.maskToken(null));
    }

    private static MealAnalysisSnapshotStore.Snapshot snapshot() {
        var item = new MealAnalysisSnapshotStore.SnapshotItem(
                "米饭", "1", null, 1, new BigDecimal("83.2"), "LIBRARY", "UNRESOLVED", 50);
        return new MealAnalysisSnapshotStore.Snapshot(
                1L, 8L, "breakfast", System.currentTimeMillis(), "qwen3-vl-plus", List.of(item));
    }
}
