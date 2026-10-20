package com.threadfit.glucopilot.ai.business.chat.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.threadfit.glucopilot.ai.memory.assembly.ChatContextShard;
import com.threadfit.glucopilot.ai.business.chat.service.ChatContextRecorder;

/**
 * 一轮 prompt 的分片审计（对应 chat_context，③ 期起开始写入）。
 *
 * <h2>这张表原来为什么是空的</h2>
 * DDL 从 V1 就建好了它（注释写着"窗口切片 + 分片审计"），但一直没有任何代码写入——
 * 于是"这一轮模型到底拿到了什么"只能靠日志猜。③ 期把它接上，只写<b>元数据</b>。
 *
 * <h2>为什么 content_text / content_json 恒为 NULL</h2>
 * 见 {@code ChatContextShard} 的类注释：正文留给 {@code chat_message}、摘要留给
 * {@code chat_session.context_summary}，这里再存一份整包 prompt 会让用户的删除权失效，
 * 且写入量随对话量线性膨胀。要定位内容，用 {@code trigger_msg_id} / {@code assistant_msg_id}
 * 回查即可。
 * <p>
 * 因此 V28 迁移<b>删掉了 {@code chk_cc_payload}（"内容必须非空"）这条 CHECK</b>：
 * 它是"这张表用来存整包窗口"那个旧设想的产物，与"只记元数据"的新约定直接冲突
 * （两个内容列都是 NULL 时会被数据库拒绝）。删的是约束，不是数据。
 *
 * <h2>为什么继承 BaseEntity 而不是只追加基类</h2>
 * 这张表有完整的审计列（created_by / gmt_modified / is_deleted…），是 BaseEntity 的形状。
 * 虽然本实现只插不改，但"跟随对话一起被清掉"是将来可能需要的语义（删会话时一并逻辑删除分片），
 * 那时 {@code @TableLogic} 已经在位。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_context")
public class ChatContextEntity extends BaseEntity {

    /** 会话 ID。 */
    private Long sessionId;

    /** 用户账号 ID（冗余一列：按用户排查"这个人的 prompt 都花在哪"时不必再 join 会话表）。 */
    private Long userId;

    /** 触发本轮的用户消息 ID。 */
    private Long triggerMsgId;

    /**
     * 本轮生成的助手消息 ID。
     * <p>
     * 分片是在<b>装配阶段</b>算出来的，而助手消息要等模型跑完才落库——因此这一列必须
     * <b>落库后回填</b>（见 {@code ChatContextRecorder}），不可以在装配时就试图填。
     */
    private Long assistantMsgId;

    /** 会话内轮次号（从 1 起）：同一会话内分片按它分组。 */
    private Integer contextRound;

    /** 分片类型：1 系统提示 / 3 历史摘要 / 5 工具结果 / 6 其他（见 {@code ChatContextShard}）。 */
    private Integer contextType;

    /** 分片键：system_prompt / long_term_memory / context_summary / tool_digest / history_messages。 */
    private String contextKey;

    /** 文本内容：本实现<b>恒为 NULL</b>（只记元数据，见类注释）。 */
    private String contentText;

    /** 结构化内容：本实现<b>恒为 NULL</b>（只记元数据，见类注释）。 */
    private String contentJson;

    /** 本分片的估算 token。 */
    private Integer tokenEstimate;

    /** 本轮调用的模型编码。 */
    private String modelCode;

    /** 本轮内各分片的展示顺序（越小越基础）。 */
    private Integer sortNo;
}
