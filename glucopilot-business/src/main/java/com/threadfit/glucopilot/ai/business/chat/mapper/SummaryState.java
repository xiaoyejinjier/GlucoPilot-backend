package com.threadfit.glucopilot.ai.business.chat.mapper;

import lombok.Data;

/**
 * 滚动摘要的读取结果：正文 + 覆盖到哪一条消息（③ 期）。
 *
 * <p>为什么把两者一起取回来：装配日志要同时打「摘要多少字」与「覆盖到 #几」，
 * 分两次查同一个主键行纯属浪费；而只取 {@code context_summary} 一列时是看不到覆盖位置的。
 *
 * <p>为什么用 POJO 而不是 record：本类由 MyBatis 直接填充（{@code SELECT … AS text}
 * 这类列别名映射到 setter），record 需要构造器自动映射，配置不当时会静默得到 null 字段。
 */
@Data
public class SummaryState {

    /** 摘要正文；库里为 NULL 时本字段也为 null。 */
    private String text;

    /** 摘要覆盖到的最后一条消息 id；从未生成摘要时为 null。 */
    private Long uptoMsgId;
}
