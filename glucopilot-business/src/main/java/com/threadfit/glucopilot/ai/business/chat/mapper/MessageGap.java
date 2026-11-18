package com.threadfit.glucopilot.ai.business.chat.mapper;

import lombok.Data;

/**
 * 「摘要与窗口之间的空洞」统计（③ 期）。
 *
 * <p>空洞 = 既没被滚动摘要覆盖、也没进本轮窗口的那几条消息。
 * 数据来自一条聚合查询（见 {@link ChatSessionMapper#selectMessageGap}），
 * 只在真的发生裁剪（{@code droppedHistory > 0}）时才查，平时零开销。
 */
@Data
public class MessageGap {

    /** 空洞里的消息条数；0 表示摘要与窗口首尾相接。 */
    private Integer missing;

    /** 空洞里最早的那条消息 id；{@code missing == 0} 时为 null。 */
    private Long firstMissingId;
}
