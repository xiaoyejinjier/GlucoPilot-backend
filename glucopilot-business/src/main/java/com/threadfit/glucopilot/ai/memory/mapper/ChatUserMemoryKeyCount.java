package com.threadfit.glucopilot.ai.memory.mapper;

import lombok.Data;

/**
 * 「每一类各有多少条生效记忆」的投影。
 *
 * <p>用一个 POJO 承接 {@code GROUP BY} 的结果，而不是 {@code List<Map<String,Object>>}：
 * MySQL 与 H2 对未加别名的列名大小写处理不同（前者原样、后者转大写），
 * 直接按 Map 的键取值会在单测与生产之间"随机"取到 null。
 * 这里把别名写死成驼峰（见 {@code ChatUserMemoryMapper#countActiveGroupByKey}），
 * 由 MyBatis 做一次映射，两边都只认 {@link #memoryKey} 与 {@link #total}。
 */
@Data
public class ChatUserMemoryKeyCount {

    /** 记忆类，如 {@code diet_taboo}。 */
    private String memoryKey;

    /** 该类下的生效条数。 */
    private Integer total;
}
