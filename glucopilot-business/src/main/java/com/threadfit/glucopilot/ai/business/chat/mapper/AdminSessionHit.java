package com.threadfit.glucopilot.ai.business.chat.mapper;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端会话列表的查询行（只给 {@code ChatSessionMapper.selectAdminPage} 用）。
 *
 * <h2>为什么不直接返回 {@code ChatSession} 实体</h2>
 * 列表需要两个实体上没有的值：用户昵称（{@code user_profile.nickname}，跨表）与
 * 「有没有滚动摘要」（{@code context_summary} 非空的布尔化）。放进实体就得加
 * {@code @TableField(exist = false)} 的伪字段，等于让 ORM 实体承担"某个查询的投影"；
 * 单独一个 POJO 更诚实，也不会让别处的 {@code insert}/{@code update} 意外带上它们。
 * <p>
 * 用 POJO 而不是 {@code Map}：列名大小写随驱动/方言变化（MySQL 小写别名、H2 大写），
 * 按字符串键取值会在测试与生产之间悄悄错位。
 */
@Data
public class AdminSessionHit {

    private Long id;
    private Long userId;
    /** 来自 {@code user_profile.nickname}；用户没填过昵称则为 null（由 DTO 决定怎么展示）。 */
    private String userNickname;
    private String title;
    private Integer sessionStatus;
    private Integer sessionType;
    private String modelCode;
    private String systemPromptVer;
    private Integer msgCount;
    private LocalDateTime lastMsgAt;
    private LocalDateTime gmtCreate;
    /** 1 = 已有滚动摘要，0 = 没有（SQL 里 CASE 出来的，避免把整段摘要读出来）。 */
    private Integer hasSummary;
}
