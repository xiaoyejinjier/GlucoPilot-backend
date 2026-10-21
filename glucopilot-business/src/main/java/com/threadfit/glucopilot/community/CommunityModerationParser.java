package com.threadfit.glucopilot.community;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把模型输出解析成 {@link ModerationVerdict}（纯函数，无依赖，可单测）。
 *
 * <h2>为什么是"字段清单"而不是 JSON</h2>
 * 审核提示词住在 {@code ai_prompt_template} 里，要经过 ST 模板渲染，而
 * <b>正文里的花括号/方括号会被当成变量表达式</b>——V22/V23/V27 三次因此启动失败。
 * 所以输出格式改用逐行 {@code 字段名：值} 的清单（冒号中英文都接受）。
 *
 * <h2>失败一律 ERROR，绝不放行</h2>
 * 空输出、超长、verdict 不在三态里、REJECT 却没有任何有效类别 —— 全部返回
 * {@link ModerationVerdict#error(String)}。审核系统里"模型没给出可解析结论"与"内容干净"
 * 是两件完全不同的事，把它们混在一起是这类系统最常见的事故来源。
 *
 * <h2>类别必须在白名单内</h2>
 * 模型自创的类别直接丢弃（与长期记忆的键白名单同一思路）：留着它会让
 * "哪类最多/误杀集中在哪类"的统计失真。丢弃后若一条有效命中都不剩而 verdict 又是 REJECT，
 * 视为"模型说有问题但说不出是什么问题" —— 仍然 ERROR，交人工判断。
 */
public final class CommunityModerationParser {

    /** 模型输出上限：超过这个长度基本可以确定不是本协议的输出（例如把正文复述了一遍）。 */
    static final int MAX_RESPONSE_CHARS = 6000;

    /** reason 落库前截断到 200 字：再长对运营没有价值，反而把列宽（1000）吃掉。 */
    static final int MAX_REASON_CHARS = 200;

    private CommunityModerationParser() {
    }

    /**
     * 解析模型输出。
     *
     * @param raw 模型返回的整段文本，可为 null
     * @return 一定非 null：解析不成功时返回 {@code ERROR}（含原因，便于写进 ai_reason 排障）
     */
    public static ModerationVerdict parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ModerationVerdict.error("模型返回空内容；未自动放行，请人工复核");
        }
        if (raw.length() > MAX_RESPONSE_CHARS) {
            return ModerationVerdict.error("模型返回内容异常（" + raw.length() + " 字，超过 "
                    + MAX_RESPONSE_CHARS + "）；未自动放行，请人工复核");
        }

        Map<String, String> fields = toFields(raw);
        String verdict = normalizeVerdict(fields.get("verdict"));
        if (verdict == null) {
            return ModerationVerdict.error("模型输出缺少合法 verdict（应为 PASS 或 REJECT）；未自动放行");
        }
        List<ModerationVerdict.Hit> hits = toHits(fields);
        String reason = truncate(fields.get("reason"));

        if (ModerationVerdict.PASS.equals(verdict)) {
            // 通过时不保留命中明细：PASS 与"有命中"同时出现说明模型自相矛盾，
            // 这类输出按"干净但记理由"处理，不产生证据行（避免污染类别统计）
            return ModerationVerdict.pass(reason);
        }
        if (hits.isEmpty()) {
            return ModerationVerdict.error("模型判定 REJECT 但未给出有效违规类别；未自动放行，请人工复核");
        }
        ModerationVerdict.Hit top = hits.stream()
                .max(Comparator.comparingInt(ModerationVerdict.Hit::severity)
                        .thenComparingInt(ModerationVerdict.Hit::confidence))
                .orElseThrow();
        return new ModerationVerdict(ModerationVerdict.REJECT, top.category(), top.severity(),
                top.confidence(), reason.isEmpty() ? top.category().displayName() : reason, hits);
    }

    /** verdict 只认三态里的两个（ERROR 由调用方在异常路径产生，模型不该自己输出它）。 */
    private static String normalizeVerdict(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (ModerationVerdict.PASS.equals(normalized) || ModerationVerdict.REJECT.equals(normalized)) {
            return normalized;
        }
        // 模型偶尔会把整行写成 "verdict：PASS 或 REJECT"，宽容地取出第一个合法值
        if (normalized.contains(ModerationVerdict.REJECT)) {
            return ModerationVerdict.REJECT;
        }
        if (normalized.contains(ModerationVerdict.PASS)) {
            return ModerationVerdict.PASS;
        }
        return null;
    }

    /**
     * 把逐行 {@code 字段名：值} 摊平成 map。
     * <p>
     * 同一字段可能出现多行（模型按"每条命中"重复输出 category/severity/confidence/evidence），
     * 因此多值字段用列表承载、单值字段取第一次出现的值。
     */
    private static Map<String, String> toFields(String raw) {
        Map<String, StringBuilder> merged = new LinkedHashMap<>();
        for (String line : raw.split("\\R")) {
            String trimmed = stripDecorations(line);
            if (trimmed.isEmpty()) {
                continue;
            }
            int split = firstSeparator(trimmed);
            if (split <= 0) {
                continue;
            }
            String key = trimmed.substring(0, split).trim().toLowerCase(java.util.Locale.ROOT);
            String value = trimmed.substring(split + 1).trim();
            if (key.isEmpty()) {
                continue;
            }
            // 多值字段用换行拼起来，解析命中时再拆
            StringBuilder existing = merged.get(key);
            if (existing == null) {
                merged.put(key, new StringBuilder(value));
            } else {
                existing.append('\n').append(value);
            }
        }
        Map<String, String> fields = new LinkedHashMap<>();
        merged.forEach((key, value) -> fields.put(key, value.toString()));
        return fields;
    }

    /** 去掉 Markdown 装饰（模型偶尔无视"不要 Markdown"）：列表符号、代码围栏、粗体星号。 */
    private static String stripDecorations(String line) {
        String value = line.trim();
        if (value.startsWith("```")) {
            return "";
        }
        while (value.startsWith("-") || value.startsWith("*") || value.startsWith("•")) {
            value = value.substring(1).trim();
        }
        value = value.replace("**", "").replace("`", "");
        return value.trim();
    }

    /** 字段名与值之间的分隔符：中文冒号优先，其次英文冒号；等号兼容模型偶发写法。 */
    private static int firstSeparator(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '：' || c == ':' || c == '=') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 解析全部命中。
     * <p>
     * 单条命中的四个字段（category/severity/confidence/evidence）按出现顺序一一对应；
     * 类别不在白名单里的那一条整条丢弃（连同它的 severity/evidence），避免"类别被丢弃但严重度还在"的半条记录。
     */
    private static List<ModerationVerdict.Hit> toHits(Map<String, String> fields) {
        String[] categories = splitValues(fields.get("category"));
        String[] severities = splitValues(fields.get("severity"));
        String[] confidences = splitValues(fields.get("confidence"));
        String[] evidences = splitValues(fields.get("evidence"));

        List<ModerationVerdict.Hit> hits = new ArrayList<>(categories.length);
        for (int i = 0; i < categories.length; i++) {
            String code = categories[i];
            if (ModerationCategories.NONE.equalsIgnoreCase(code)) {
                continue;
            }
            var category = ModerationCategories.of(code);
            if (category.isEmpty()) {
                continue;
            }
            int severity = parseIntAt(severities, i, category.get().defaultSeverity());
            int confidence = parseIntAt(confidences, i, 0);
            String excerpt = i < evidences.length ? evidences[i] : "";
            hits.add(new ModerationVerdict.Hit(category.get(), clampSeverity(severity), clampConfidence(confidence),
                    excerpt));
        }
        return hits;
    }

    private static String[] splitValues(String value) {
        if (value == null || value.isBlank()) {
            return new String[0];
        }
        return java.util.Arrays.stream(value.split("\\R"))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toArray(String[]::new);
    }

    /** 缺少第 i 个值时用默认值（模型可能只给了一条 severity，却输出了两条 category）。 */
    private static int parseIntAt(String[] values, int index, int fallback) {
        if (index >= values.length) {
            return fallback;
        }
        try {
            return Integer.parseInt(values[index].replaceAll("[^0-9-]", ""));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** 严重度夹到 1~3：模型给 0 或 9 都不可信，按类别默认值兜底更稳。 */
    private static int clampSeverity(int severity) {
        return severity < 1 || severity > 3 ? 2 : severity;
    }

    private static int clampConfidence(int confidence) {
        return Math.max(0, Math.min(100, confidence));
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return "";
        }
        String value = reason.replaceAll("\\R", " ").trim();
        return value.length() <= MAX_REASON_CHARS ? value : value.substring(0, MAX_REASON_CHARS);
    }
}
