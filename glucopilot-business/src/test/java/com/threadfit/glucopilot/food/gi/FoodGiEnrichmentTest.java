package com.threadfit.glucopilot.food.gi;

import com.threadfit.glucopilot.food.service.FoodCatalogCache;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FoodGiEnrichmentTest {
    private FoodGiMapper.Candidate item() {
        var item = new FoodGiMapper.Candidate();
        item.setFoodId(1L); item.setFoodName("燕麦"); item.setCategoryCode(1);
        return item;
    }
    private FoodGiResult reference() {
        return new FoodGiResult("REFERENCE", new BigDecimal("55"), "Oatmeal (unpackaged)",
                "https://pmc.ncbi.nlm.nih.gov/articles/PMC4087878/", "按普通非即食燕麦粥参考");
    }

    @Test void successfulFillAdvancesCacheAndRecordsSourceTogether() {
        var mapper = mock(FoodGiMapper.class); var cache = mock(FoodCatalogCache.class);
        var item = item(); var result = reference();
        when(mapper.lockClaim(1L, "lease")).thenReturn(1L);
        when(mapper.fillGi(item, result.value())).thenReturn(1);
        when(mapper.complete(item, "lease", result)).thenReturn(1);
        new FoodGiWriter(mapper, cache).complete(item, "lease", result);
        var order = inOrder(mapper, cache);
        order.verify(mapper).lockClaim(1L, "lease");
        order.verify(mapper).fillGi(item, result.value());
        order.verify(cache).advanceCatalogVersion();
        order.verify(mapper).complete(item, "lease", result);
    }

    @Test void staleLeaseAndConcurrentManualEditCannotOverwriteGi() {
        var mapper = mock(FoodGiMapper.class); var cache = mock(FoodCatalogCache.class);
        var writer = new FoodGiWriter(mapper, cache); var item = item();
        when(mapper.lockClaim(1L, "old")).thenReturn(null);
        writer.complete(item, "old", reference());
        verify(mapper, never()).fillGi(any(), any());
        when(mapper.lockClaim(1L, "current")).thenReturn(1L);
        when(mapper.complete(eq(item), eq("current"), any())).thenReturn(1);
        writer.complete(item, "current", reference());
        verify(cache, never()).advanceCatalogVersion();
        verify(mapper).complete(eq(item), eq("current"), argThat(r -> r.value() == null && r.status().equals("SKIPPED")));
    }

    @Test void noApplicableGiRecordsReasonWithoutWritingZero() {
        var mapper = mock(FoodGiMapper.class); var cache = mock(FoodCatalogCache.class);
        var item = item(); var result = new FoodGiResult("NOT_APPLICABLE", null, null, null, "无适用GI");
        when(mapper.lockClaim(1L, "lease")).thenReturn(1L);
        when(mapper.complete(item, "lease", result)).thenReturn(1);
        new FoodGiWriter(mapper, cache).complete(item, "lease", result);
        verify(mapper, never()).fillGi(any(), any());
        verifyNoInteractions(cache);
    }

    @Test void knownReferenceAvoidsNetworkAndOnlyClaimOwnerCompletes() {
        var mapper = mock(FoodGiMapper.class); var online = mock(SydneyGiLookupClient.class);
        var writer = mock(FoodGiWriter.class); var item = item();
        when(mapper.pending()).thenReturn(List.of(item));
        when(mapper.claim(eq(item), anyString())).thenReturn(1);
        var reference = new FoodGiMapper.Reference(); reference.setStatus("REFERENCE");
        reference.setGiValue(new BigDecimal("55"));
        when(mapper.reference("燕麦", 1)).thenReturn(reference);
        var job = new FoodGiLookupJob(mapper, online, writer);
        job.lookupMissing();
        verify(writer).complete(eq(item), anyString(), argThat(r -> new BigDecimal("55").equals(r.value())));
        verifyNoInteractions(online);
        reset(writer);
        when(mapper.claim(eq(item), anyString())).thenReturn(0);
        job.lookupMissing(); verifyNoInteractions(writer);
    }

    @Test void externalFailureSchedulesRetryWithoutInventingValue() throws Exception {
        var mapper = mock(FoodGiMapper.class); var online = mock(SydneyGiLookupClient.class);
        var writer = mock(FoodGiWriter.class); var item = item();
        when(mapper.pending()).thenReturn(List.of(item));
        when(mapper.claim(eq(item), anyString())).thenReturn(1);
        when(online.lookup("燕麦")).thenThrow(new java.io.IOException("source unavailable"));
        new FoodGiLookupJob(mapper, online, writer).lookupMissing();
        verify(writer).complete(eq(item), anyString(), argThat(r -> "RETRY".equals(r.status()) && r.value() == null));
    }

    @Test void slowLookupUsesIndependentWorkerAndDoesNotQueueDuplicateBatches() throws Exception {
        var mapper = mock(FoodGiMapper.class);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(mapper.enqueueMissing()).thenAnswer(call -> {
            entered.countDown(); release.await(3, java.util.concurrent.TimeUnit.SECONDS); return 0;
        });
        when(mapper.pending()).thenReturn(List.of());
        var job = new FoodGiLookupJob(mapper, mock(SydneyGiLookupClient.class), mock(FoodGiWriter.class));
        try {
            job.scheduleLookup();
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            job.scheduleLookup();
            verify(mapper, times(1)).enqueueMissing();
        } finally { release.countDown(); job.shutdown(); }
    }
}
