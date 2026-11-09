package com.threadfit.glucopilot.ai.business.chat.mapper;

import lombok.Data;

/**
 * 「某会话记了几轮分片」的聚合行（{@code ChatContextMapper.selectRoundCounts}）。
 *
 * <p>选 POJO 而非 {@code Map} 的理由同 {@link AdminSessionHit}：{@code COUNT(...)} 的别名
 * 在 MySQL 与 H2 上大小写不一致，按字符串键取值会在单测（H2）与生产（MySQL）之间错位。
 */
@Data
public class SessionRoundCount {

    private Long sessionId;
    /** {@code COUNT(DISTINCT context_round)}：一轮装配对应一个取值。 */
    private Integer rounds;
}
