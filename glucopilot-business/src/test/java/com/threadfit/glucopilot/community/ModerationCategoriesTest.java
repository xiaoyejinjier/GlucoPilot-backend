package com.threadfit.glucopilot.community;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 违规类别枚举的契约。
 * <p>
 * 类别是"模型白名单 + 运营统计维度"，两件事都要求它集合稳定：
 * code 重复会让统计串类，缺失 {@code medical_claim}/{@code medical_advice} 会让本产品最在意的
 * 医疗风险失去单独口径。
 */
class ModerationCategoriesTest {

    @Test
    void code唯一且非空() {
        Set<String> codes = new HashSet<>(ModerationCategories.codes());
        assertEquals(ModerationCategories.values().length, codes.size(), "code 不能重复");
        assertFalse(codes.contains(null));
        assertFalse(codes.contains(""), "空 code 会让「没有违规」与「类别缺失」混在一起");
        assertFalse(codes.contains(ModerationCategories.NONE), "none 是模型表示「无违规」的保留字，不能当类别");
    }

    /** 医疗两类必须单独存在：控糖社区最高频的风险不是通用色情暴恐。 */
    @Test
    void 医疗类风险有独立类别() {
        assertTrue(ModerationCategories.of("medical_claim").isPresent());
        assertTrue(ModerationCategories.of("medical_advice").isPresent());
        assertEquals(3, ModerationCategories.MEDICAL_CLAIM.defaultSeverity());
        assertEquals(3, ModerationCategories.MEDICAL_ADVICE.defaultSeverity());
    }

    @Test
    void 能按code解析且大小写不敏感() {
        Optional<ModerationCategories> upper = ModerationCategories.of("  MEDICAL_CLAIM ");
        assertTrue(upper.isPresent());
        assertSame(ModerationCategories.MEDICAL_CLAIM, upper.get());

        assertTrue(ModerationCategories.of("not_a_category").isEmpty());
        assertTrue(ModerationCategories.of(null).isEmpty());
        assertTrue(ModerationCategories.of("").isEmpty());
    }

    @Test
    void 每个类别都有中文名与合法默认严重度() {
        List<ModerationCategories> all = List.of(ModerationCategories.values());
        for (ModerationCategories category : all) {
            assertFalse(category.displayName().isBlank(), category + " 缺少中文名（管理端要直接展示）");
            assertTrue(category.defaultSeverity() >= 1 && category.defaultSeverity() <= 3,
                    category + " 的默认严重度必须在 1~3");
        }
    }

    /** 灌水类默认最低严重度：它只记录、不拦截。 */
    @Test
    void 灌水类默认严重度最低() {
        assertEquals(1, ModerationCategories.OFF_TOPIC.defaultSeverity());
    }
}
