package com.threadfit.glucopilot.prediction.service;

import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlucoseGoalResolverTest {

    private static HealthView health(Integer type, Integer goalProfile) {
        return HealthView.builder()
                .diabetesType(type)
                .glucoseGoalProfile(goalProfile)
                .build();
    }

    @Test
    void nullProfileDefaultsToGeneral() {
        GlucoseGoalResolver.ResolvedGoal g = GlucoseGoalResolver.resolve(health(null, null));
        assertEquals(GlucoseGoalResolver.Profile.GENERAL, g.effectiveProfile());
        assertEquals(GlucoseGoalResolver.TargetSource.GENERAL, g.source());
        assertValue("4.4", g.targetLower());
        assertValue("10.0", g.targetUpper());
    }

    @Test
    void explicitGeneralProfile() {
        GlucoseGoalResolver.ResolvedGoal g = GlucoseGoalResolver.resolve(health(2, 0));
        assertEquals(GlucoseGoalResolver.Profile.GENERAL, g.effectiveProfile());
        assertEquals(GlucoseGoalResolver.TargetSource.GENERAL, g.source());
        assertValue("10.0", g.targetUpper());
    }

    @Test
    void youngProfile() {
        GlucoseGoalResolver.ResolvedGoal g = GlucoseGoalResolver.resolve(health(null, 1));
        assertEquals(GlucoseGoalResolver.Profile.YOUNG_NO_COMPLICATION, g.effectiveProfile());
        assertEquals(GlucoseGoalResolver.TargetSource.PROFILE, g.source());
        assertValue("4.4", g.targetLower());
        assertValue("7.8", g.targetUpper());
    }

    @Test
    void olderProfile() {
        GlucoseGoalResolver.ResolvedGoal g = GlucoseGoalResolver.resolve(health(null, 2));
        assertEquals(GlucoseGoalResolver.Profile.OLDER_HYPOGLYCEMIA, g.effectiveProfile());
        assertEquals(GlucoseGoalResolver.TargetSource.PROFILE, g.source());
        assertValue("5.0", g.targetLower());
        assertValue("10.5", g.targetUpper());
    }

    @Test
    void profile3ForcesGdm() {
        GlucoseGoalResolver.ResolvedGoal g = GlucoseGoalResolver.resolve(health(null, 3));
        assertEquals(GlucoseGoalResolver.Profile.GDM, g.effectiveProfile());
        assertValue("3.9", g.targetLower());
        assertValue("6.7", g.targetUpper());
    }

    @Test
    void diabetesType3ForcesGdmEvenWithoutProfile() {
        // R6 取严：diabetesType=3 与 glucoseGoalProfile 冲突时任一为真即按 GDM
        GlucoseGoalResolver.ResolvedGoal g = GlucoseGoalResolver.resolve(health(3, 0));
        assertEquals(GlucoseGoalResolver.Profile.GDM, g.effectiveProfile());
        assertValue("6.7", g.targetUpper());
    }

    @Test
    void illegalProfileFallsBackToGlobalDefault() {
        GlucoseGoalResolver.ResolvedGoal g = GlucoseGoalResolver.resolve(health(null, 99));
        assertEquals(GlucoseGoalResolver.Profile.GENERAL, g.effectiveProfile());
        assertEquals(GlucoseGoalResolver.TargetSource.GLOBAL, g.source());
        assertValue("4.4", g.targetLower());
        assertValue("10.0", g.targetUpper());
    }

    private static void assertValue(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "期望 " + expected + " 实际 " + actual);
    }
}