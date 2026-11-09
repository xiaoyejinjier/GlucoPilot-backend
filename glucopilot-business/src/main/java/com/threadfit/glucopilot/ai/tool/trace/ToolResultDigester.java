package com.threadfit.glucopilot.ai.tool.trace;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 工具结果的"关键字段摘要"（B3 后半）。
 *
 * <h2>为什么不能直接截断前 512 字</h2>
 * 工具返回是 JSON，前 512 字往往刚好停在数组第一项中间——既不完整也不省事，
 * 模型看到半截 JSON 反而更容易瞎猜。这里做的是"结构感知的压缩"：
 * <ul>
 *   <li>保留标量字段（数值/布尔/短字符串），它们是"关键字段"（avg、count、recordedDays…）；</li>
 *   <li>数组只保留前 {@value #MAX_ARRAY_ITEMS} 项并标注总数（{@code "records":[...共12项]}）；</li>
 *   <li>删除空值字段与超长字符串（截断到 {@value #MAX_STRING_CHARS} 字）。</li>
 * </ul>
 * 最后再按 {@code maxChars} 硬截断兜底——结构压缩之后仍然超长时，至少截断点不在关键字段中间。
 *
 * <h2>为什么不存完整返回</h2>
 * 完整返回是用户的血糖、饮食等原始数据；多存一份就多一份隐私与脱敏责任。摘要的目的是
 * "让模型记得上一轮查到了什么"，不是"重建一份数据副本"。
 */
@Component
public class ToolResultDigester {

    /** 数组最多保留几项。 */
    private static final int MAX_ARRAY_ITEMS = 3;

    private static final int MAX_STRING_CHARS = 80;

    private static final int MAX_DEPTH = 3;

    /**
     * 解析用的 mapper：<b>进程内共享一份</b>。
     * <p>
     * {@code JsonMapper} 构造一次要建序列化/反序列化缓存，是重量级对象；本类每次工具调用都会走一遍，
     * 原来每次 {@code digest()} 都 {@code JsonMapper.builder().build()} 一次，属于白白新建。
     * Jackson 的 mapper 配置完成后是线程安全的，静态复用即可。
     * <p>
     * 这里刻意不复用 Spring 注入的 {@code ObjectMapper}（见 {@code JsonUtils}）：本类只用它把字符串
     * 解析成 {@code JsonNode}，不做"对象 ↔ JSON"的语义转换，因此 Boot 侧的模块/日期格式等配置与它无关，
     * 也就不必为它引入依赖。
     */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 压缩空白用；正则 {@code Pattern} 同样只编译一次。 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** 压缩成不超过 maxChars 的摘要文本；无法解析为 JSON 时退化为"压缩空白 + 截断"。 */
    public String digest(String toolResult, int maxChars) {
        if (!StringUtils.hasText(toolResult)) {
            return null;
        }
        int limit = Math.max(maxChars, 32);
        String compact;
        try {
            JsonNode node = MAPPER.readTree(toolResult);
            StringBuilder sb = new StringBuilder();
            write(node, sb, 1);
            compact = sb.toString();
        } catch (RuntimeException e) {
            // 工具返回不一定是 JSON（某些工具直接返回一句中文）。这不是异常情况，退化为文本压缩
            compact = WHITESPACE.matcher(toolResult).replaceAll(" ").trim();
        }
        if (compact.length() <= limit) {
            return compact;
        }
        return compact.substring(0, limit - 1) + "…";
    }

    private static void write(JsonNode node, StringBuilder sb, int depth) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                JsonNode value = entry.getValue();
                if (value == null || value.isNull()) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(entry.getKey()).append(':');
                appendValue(value, sb, depth);
            }
            sb.append('}');
            return;
        }
        appendValue(node, sb, depth);
    }

    private static void appendValue(JsonNode value, StringBuilder sb, int depth) {
        if (value.isArray()) {
            sb.append('[');
            int shown = 0;
            for (JsonNode item : value) {
                if (shown >= MAX_ARRAY_ITEMS) {
                    break;
                }
                if (shown > 0) {
                    sb.append(',');
                }
                if (depth >= MAX_DEPTH) {
                    sb.append('…');
                } else {
                    write(item, sb, depth + 1);
                }
                shown++;
            }
            if (value.size() > MAX_ARRAY_ITEMS) {
                sb.append(",共").append(value.size()).append("项");
            }
            sb.append(']');
            return;
        }
        if (value.isObject()) {
            if (depth >= MAX_DEPTH) {
                sb.append('{').append(value.size()).append("字段}");
            } else {
                write(value, sb, depth + 1);
            }
            return;
        }
        String text = value.asString();
        if (text.length() > MAX_STRING_CHARS) {
            text = text.substring(0, MAX_STRING_CHARS) + "…";
        }
        sb.append(WHITESPACE.matcher(text).replaceAll(" "));
    }

    /** 兜底用的简单压缩（单测/其它模块复用）。 */
    static String collapse(String text) {
        if (text == null) {
            return null;
        }
        return WHITESPACE.matcher(text).replaceAll(" ").trim();
    }

    /** 保留供单测断言用的正则（检查是否出现"共N项"标记）。 */
    static final Pattern ITEM_COUNT = Pattern.compile("共(\\d+)项");
}
