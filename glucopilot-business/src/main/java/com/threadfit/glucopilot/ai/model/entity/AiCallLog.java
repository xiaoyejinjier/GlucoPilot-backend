package com.threadfit.glucopilot.ai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次模型调用的账单行（V75）。
 *
 * <p><b>只追加、无逻辑删除</b>：账单的事实不需要"删"，只需要保留期（90 天）之后物理清掉。
 * 因此本实体没有 {@code is_deleted}/{@code deleted_by}，也没有 {@code updated_by}——写一次就不再改。
 *
 * <p><b>与 {@code chat_message} 的区别</b>：那里是"一条消息的属性"（用户能看到的东西），
 * 这里是"一次模型调用花了多少"。一轮对话里工具回环会调多次模型，消息只有一条，所以两者
 * 天生不是 1:1；账单以本表为准，消息表上的用量只用于"这条回答花了多少"。
 *
 * <p><b>不存正文与提示词</b>：只记场景/模型/用量/耗时/成败，避免在账本里再复制一份用户内容。
 */
@Data
@TableName("ai_call_log")
public class AiCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 场景（{@code SceneCode} 枚举名）。刻意用枚举名而不是 yaml 小写键：报表按它分组，改名即断。 */
    private String sceneCode;

    /** 通道：text / vision。 */
    private String channel;

    /** 模型身份（{@code ai_model_config.model_code}），不是 endpoint。 */
    private String modelCode;

    /** 实际发给厂商的模型名。 */
    private String modelEndpoint;

    /** 用户 ID；系统调用为空。 */
    private Long userId;

    /** 会话 ID；非对话场景为空。 */
    private Long sessionId;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer cacheHitTokens;

    private Integer cacheMissTokens;

    private Integer latencyMs;

    /** 1=流式，0=同步。 */
    private Integer stream;

    /** 1 成功、0 失败。 */
    private Integer ok;

    /** 失败时的异常类名（不记消息正文）。 */
    private String errorCode;

    private LocalDateTime gmtCreate;
}
