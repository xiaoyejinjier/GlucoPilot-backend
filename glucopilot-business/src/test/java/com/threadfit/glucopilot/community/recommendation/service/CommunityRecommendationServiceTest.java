package com.threadfit.glucopilot.community.recommendation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.threadfit.glucopilot.community.recommendation.entity.ArticleTagSignal;
import com.threadfit.glucopilot.community.recommendation.mapper.CommunityRecommendationMapper;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;

class CommunityRecommendationServiceTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 28, 16, 0);

    private ArticleTagSignal signal(long tag, int days, double weight) {
        var row = new ArticleTagSignal();
        row.setTagId(tag);
        row.setSignalDate(now.toLocalDate().minusDays(days));
        row.setWeight(weight);
        return row;
    }

    @Test
    void fourteenDayHalfLifeAndL2NormalizationProduceExpectedCosineInputs() {
        var profile =
                CommunityRecommendationService.normalize(
                        List.of(signal(1, 0, 8), signal(2, 14, 8)), now);
        assertEquals(2, profile.tags().size());
        assertEquals(2.0, profile.tags().get(0).weight() / profile.tags().get(1).weight(), 1e-10);
        assertEquals(
                1.0,
                profile.tags().stream().mapToDouble(t -> t.weight() * t.weight()).sum(),
                1e-10);
    }

    @Test
    void staleFutureZeroAndInvalidSignalsDoNotCreateInterests() {
        var empty =
                CommunityRecommendationService.normalize(
                        List.of(
                                signal(1, 30, 8),
                                signal(2, -1, 8),
                                signal(3, 0, 0),
                                signal(4, 0, Double.NaN)),
                        now);
        assertTrue(empty.tags().isEmpty());
        var valid = CommunityRecommendationService.normalize(List.of(signal(1, 29, 8)), now);
        assertEquals(1, valid.tags().size());
        assertEquals(1, valid.tags().getFirst().weight());
    }

    @Test
    void aggregatesTagsAndBoundsVectorSizeDeterministically() {
        var signals = new ArrayList<ArticleTagSignal>();
        for (int id = 70; id >= 1; id--) signals.add(signal(id, 0, 1));
        signals.add(signal(70, 0, 5));
        var profile = CommunityRecommendationService.normalize(signals, now);
        assertEquals(64, profile.tags().size());
        assertEquals(70, profile.tags().getFirst().tagId());
        assertEquals(1, profile.tags().get(1).tagId());
        assertThrows(UnsupportedOperationException.class, () -> profile.tags().clear());
    }

    @Test
    void historyQueryIsBoundToViewerAndThirtyCalendarDays() {
        var mapper = mock(CommunityRecommendationMapper.class);
        when(mapper.signals(36, now.toLocalDate().minusDays(29).atStartOfDay(), now))
                .thenReturn(List.of(signal(1, 0, 3)));
        var service = new CommunityRecommendationService(mapper);
        var profile = service.prepare(36, now);
        assertEquals(1, profile.tags().size());
        verify(mapper).signals(36, now.toLocalDate().minusDays(29).atStartOfDay(), now);
    }
}
