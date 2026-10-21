package com.threadfit.glucopilot.community;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审核输出解析（纯函数）。这是"模型输出 → 落库结论"的唯一关卡，因此重点不在"正常情况能解析"，
 * 而在<b>异常路径一律 ERROR 而不是 PASS</b>：
 * <ul>
 *   <li>空输出 / 超长 / 缺 verdict / verdict 非法 → ERROR（绝不放行）；</li>
 *   <li>REJECT 但类别全是自创的 → ERROR（模型说有问题却说不出是什么问题，交人工）；</li>
 *   <li>类别越界的<b>那一条</b>被丢弃，其它合法命中仍生效（不能整段作废，也不能整段采信）。</li>
 * </ul>
 * 另外固定住"宽容度"：中文冒号/英文冒号/等号、Markdown 列表符号与代码围栏、大写 verdict。
 */
class CommunityModerationParserTest {

    // ---------------------------------------------------------------- 正常路径

    @Test
    void 解析通过的判定() {
        ModerationVerdict verdict = CommunityModerationParser.parse("""
                verdict：PASS
                category：none
                severity：0
                confidence：92
                reason：客观介绍药物类别，未见普适化承诺
                """);

        assertTrue(verdict.isPass());
        assertNull(verdict.categoryCode(), "PASS 时类别写 null，才能区分「没审」与「审过且干净」");
        assertEquals(0, verdict.severity());
        assertTrue(verdict.hits().isEmpty(), "PASS 不产生证据行，避免污染类别统计");
        assertEquals("客观介绍药物类别，未见普适化承诺", verdict.reason());
    }

    @Test
    void 解析拒绝的判定并取严重度最高的命中作主类别() {
        ModerationVerdict verdict = CommunityModerationParser.parse("""
                verdict：REJECT
                category：scam_ad
                severity：2
                confidence：80
                category：medical_claim
                severity：3
                confidence：95
                reason：声称可以替代降糖药
                """);

        assertTrue(verdict.isReject());
        assertEquals("medical_claim", verdict.categoryCode(), "主类别取严重度最高的那条");
        assertEquals(3, verdict.severity());
        assertEquals(95, verdict.confidence());
        assertEquals(2, verdict.hits().size());
    }

    /** 中文冒号、英文冒号、等号、Markdown 列表符号、代码围栏、大写 verdict 都要能解析。 */
    @Test
    void 宽容解析各种装饰与分隔符() {
        ModerationVerdict verdict = CommunityModerationParser.parse("""
                ```text
                - **verdict: reject**
                - category = medical_advice
                - severity: 3
                - confidence: 88
                * reason：给出具体剂量并劝人停药
                ```
                """);

        assertTrue(verdict.isReject());
        assertEquals("medical_advice", verdict.categoryCode());
        assertEquals(3, verdict.severity());
        assertEquals(88, verdict.confidence());
        assertTrue(verdict.reason().contains("剂量"));
    }

    /** 模型把整行写成"PASS 或 REJECT"时，优先识别出 REJECT（宁严不宽）。 */
    @Test
    void verdict写成协议说明时取更严格的一侧() {
        ModerationVerdict verdict = CommunityModerationParser.parse("""
                verdict：PASS 或 REJECT
                category：pseudo_science
                severity：2
                confidence：70
                reason：苦瓜根治糖尿病
                """);

        assertTrue(verdict.isReject());
    }

    // ---------------------------------------------------------------- 失败路径（必须 ERROR）

    @Test
    void 空输出是ERROR而不是通过() {
        assertTrue(CommunityModerationParser.parse(null).isError());
        assertTrue(CommunityModerationParser.parse("   ").isError());
    }

    @Test
    void 超长输出是ERROR() {
        String raw = "verdict：PASS\nreason：" + "字".repeat(CommunityModerationParser.MAX_RESPONSE_CHARS);

        ModerationVerdict verdict = CommunityModerationParser.parse(raw);

        assertTrue(verdict.isError());
        assertTrue(verdict.reason().contains("超过"));
    }

    @Test
    void 缺少verdict是ERROR() {
        assertTrue(CommunityModerationParser.parse("category：porn\nseverity：3\nreason：x").isError());
        assertTrue(CommunityModerationParser.parse("verdict：MAYBE\nreason：x").isError(),
                "不在三态里的取值不能当成通过，也不能当成拒绝");
    }

    @Test
    void 拒绝但类别全部越界时是ERROR() {
        ModerationVerdict verdict = CommunityModerationParser.parse("""
                verdict：REJECT
                category：广告嫌疑（自创类别）
                severity：3
                confidence：99
                reason：像是广告
                """);

        assertTrue(verdict.isError(), "模型说有问题却说不出合法类别，必须交人工而不是猜一个类别");
    }

    @Test
    void 越界类别只丢弃那一条其它命中仍生效() {
        ModerationVerdict verdict = CommunityModerationParser.parse("""
                verdict：REJECT
                category：自创类别
                severity：3
                confidence：99
                category：hate_harass
                severity：2
                confidence：75
                reason：辱骂病友
                """);

        assertTrue(verdict.isReject());
        assertEquals("hate_harass", verdict.categoryCode());
        assertEquals(1, verdict.hits().size());
    }

    @Test
    void 严重度与置信度越界被夹紧() {
        ModerationVerdict verdict = CommunityModerationParser.parse("""
                verdict：REJECT
                category：porn
                severity：9
                confidence：180
                reason：x
                """);

        assertEquals(2, verdict.severity(), "9 不可信，按默认严重度兜底");
        assertEquals(100, verdict.confidence(), "置信度夹到 0~100");
    }

    @Test
    void 缺少严重度与置信度时用类别默认值() {
        ModerationVerdict verdict = CommunityModerationParser.parse("""
                verdict：REJECT
                category：medical_claim
                reason：包治
                """);

        assertEquals(ModerationCategories.MEDICAL_CLAIM.defaultSeverity(), verdict.severity());
        assertEquals(0, verdict.confidence(), "缺省置信度为 0，不会触发高置信度自动拒绝");
    }

    @Test
    void 理由为空时用类别中文名兜底() {
        ModerationVerdict verdict = CommunityModerationParser.parse("""
                verdict：REJECT
                category：privacy
                severity：3
                confidence：90
                """);

        assertEquals(ModerationCategories.PRIVACY.displayName(), verdict.reason());
    }

    @Test
    void 超长理由被截断且保持单行() {
        ModerationVerdict verdict = CommunityModerationParser.parse(
                "verdict：PASS\nreason：" + "说明".repeat(300) + "\n下一行");

        assertFalse(verdict.isError());
        assertTrue(verdict.reason().length() <= CommunityModerationParser.MAX_REASON_CHARS);
        assertFalse(verdict.reason().contains("\n"), "落库前必须压成单行，否则会把列宽撑爆");
    }
}
