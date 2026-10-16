package com.threadfit.glucopilot.ai.business.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话内工具调用的<b>元数据</b>（V21 建表；B3 的处置决定：只记"谁被调了/成功与否/耗时"）。
 * <p>
 * 为什么需要它：在此之前，工具调用与返回都发生在 {@code ToolCallingAdvisor} 内部、不落库，
 * 于是"这条回答当时查了哪些数据"完全不可追溯——用户追问同一份数据只能让模型重新调一次工具
 * （重复计费，还可能拿到不同结果），排障时也看不出模型到底调没调、调了几次、有没有失败。
 * <p>
 * <b>刻意不存工具返回内容</b>：那是用户的血糖、饮食等健康数据，落库等于新增一份副本，
 * 权限与脱敏要单独设计；真正的用途（把工具结果回放进上下文）属于记忆阶段的事。
 * 将来要存完整 trace 时，在本表上加列即可，不必改结构。
 * <p>
 * 只追加、不改写：没有 is_deleted 与审计列。删会话时本表数据保留——它是"当时发生了什么"的事实。
 */
@Data
@TableName("chat_tool_call")
public class ChatToolCall {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话 ID（chat_session.id）。 */
    private Long sessionId;

    /** 用户 ID。 */
    private Long userId;

    /** 本轮用户消息 ID：同一轮的多次工具调用共享它，可据此关联助手回复（assistant.reply_to_msg_id）。 */
    private Long userMsgId;

    /** 本轮单飞键 chat-round:&lt;userMsgId&gt;，排障时与日志对齐。 */
    private String roundKey;

    /** 工具名（与 {@code @Tool(name=...)} 一致）。 */
    private String toolName;

    /** 同一轮内的第几次调用，从 1 开始。 */
    private Integer callIndex;

    /** 是否成功 0 失败 1 成功。 */
    private Integer ok;

    private Integer durationMs;

    /** 失败时的异常类名（只记类名，不记消息正文，避免把用户数据写进库）。 */
    private String errorType;

    /**
     * 工具结果的**关键字段摘要**（≤512 字，② 期新增）。
     * <p>
     * 用于会话记忆回放：让模型能引用"上一轮查到的数据"，而不是重新调工具。
     * 刻意只存摘要、不存完整返回——完整返回是用户的血糖/饮食原始数据，多存一份就多一份隐私责任。
     */
    private String resultDigest;

    /** 由 DDL 的 DEFAULT CURRENT_TIMESTAMP(3) 填充，代码不写。 */
    private LocalDateTime gmtCreate;
}
