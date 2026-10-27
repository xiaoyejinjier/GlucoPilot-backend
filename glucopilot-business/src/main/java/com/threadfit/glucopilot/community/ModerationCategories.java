package com.threadfit.glucopilot.community;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 社区审核的违规类别（固定枚举）。
 *
 * <h2>为什么是代码常量而不是库表</h2>
 * 模型只能在<b>固定集合</b>里选类别：自创类别会让统计与处置全部失准（"哪类最多""误杀集中在哪类"
 * 都算不出来）。这与长期记忆的键白名单（{@code UserChatMemoryService.ALLOWED_KEYS}）是同一条思路。
 * 类别本身是<b>代码语义</b>——新增一类意味着运营处置流程与前端展示也要跟着改，
 * 所以不放数据库、不做后台可配（关键词/阈值那种"内容"才适合放库）。
 *
 * <h2>为什么医疗类单独两类</h2>
 * 控糖社区最高频、危害最大的不是通用违规，而是<b>医疗断言</b>：
 * <ul>
 *   <li>{@link #MEDICAL_CLAIM}：疗效承诺（包治、根治、几天见效、可替代药物）——"这样吃谁都能停药"；</li>
 *   <li>{@link #MEDICAL_ADVICE}：停药换药、剂量调整、劝人不要就医。</li>
 * </ul>
 * 它们必须能被单独统计与单独处置（拒绝文案要带就医引导），混进 {@code scam_ad} 或 {@code pseudo_science}
 * 会让运营看不到真正的风险分布。判据与 {@code MedicalGuardAdvisor}（对话侧的硬拦截）保持同源。
 */
public enum ModerationCategories {

    PORN("porn", "色情低俗", 3),
    VIOLENCE("violence", "暴力血腥", 3),
    ILLEGAL_GOODS("illegal_goods", "违禁药品器械或偏方售卖", 3),
    /** 疗效承诺：普适化承诺才算，个人经历不算（提示词里有明确判据）。 */
    MEDICAL_CLAIM("medical_claim", "疗效承诺（包治/根治/可替代药物）", 3),
    /** 停药换药、剂量指导、劝人不要就医：与对话侧 MedicalGuardAdvisor 对齐。 */
    MEDICAL_ADVICE("medical_advice", "停药换药/剂量指导/劝人不要就医", 3),
    SCAM_AD("scam_ad", "广告引流/拉群/留下联系方式", 2),
    PRIVACY("privacy", "隐私泄露（手机号/身份证/住址/病友实名）", 3),
    HATE_HARASS("hate_harass", "辱骂/人身攻击/病耻歧视", 2),
    POLITICAL("political", "政治敏感", 3),
    MINOR("minor", "涉未成年人", 3),
    PSEUDO_SCIENCE("pseudo_science", "伪科学/明显医疗谣言", 2),
    /** 灌水与主题无关：只记录、不拦截（默认严重度 1）。 */
    OFF_TOPIC("off_topic", "与控糖社区无关的灌水", 1);

    /** 模型表示"没有违规"时要求的类别取值；解析器见到它等价于无命中。 */
    public static final String NONE = "none";

    private static final Map<String, ModerationCategories> BY_CODE;

    static {
        Map<String, ModerationCategories> byCode = new LinkedHashMap<>();
        for (ModerationCategories category : values()) {
            byCode.put(category.code, category);
        }
        BY_CODE = Map.copyOf(byCode);
    }

    private final String code;
    private final String displayName;
    private final int defaultSeverity;

    ModerationCategories(String code, String displayName, int defaultSeverity) {
        this.code = code;
        this.displayName = displayName;
        this.defaultSeverity = defaultSeverity;
    }

    /** 模型输出里使用的 code（如 {@code medical_claim}）。 */
    public String code() {
        return code;
    }

    /** 给运营看的中文名（管理端直接展示，前端不再维护一份映射）。 */
    public String displayName() {
        return displayName;
    }

    /**
     * 该类别的默认严重度（1 低 / 2 中 / 3 高）。
     * <p>
     * 模型给的 severity 缺失或越界时用它兜底：宁可高估严重度（多转人工），也不要低估（放过高危内容）。
     */
    public int defaultSeverity() {
        return defaultSeverity;
    }

    /** 全部 code（供提示词文档、测试与解析器做白名单校验）。 */
    public static List<String> codes() {
        return BY_CODE.keySet().stream().toList();
    }

    /** 按 code 精确匹配（大小写与小写化都在这里定死：模型偶发输出大写也能认）。 */
    public static Optional<ModerationCategories> of(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_CODE.get(code.trim().toLowerCase(java.util.Locale.ROOT)));
    }
}
