package com.threadfit.glucopilot.common.audit;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.AppendOnlyEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 运营操作审计（对应 ops_audit_log）。
 *
 * <h2>为什么它必须继承 {@link AppendOnlyEntity}</h2>
 * 审计的价值全在"事后不可改"：一旦有 {@code updated_by}/{@code gmt_modified}，就会出现
 * "这条记录说谁在什么时候做过什么"与"这条记录本身被谁改过"两个互相矛盾的事实，
 * 而后者没人能证明。因此这张表<b>只追加</b>——没有修改时间、没有逻辑删除列，
 * 业务数据被删掉之后，审计行仍然是唯一能说明"当时发生了什么"的东西（PRD 数据规范第 8 条）。
 * <p>
 * 与之配套的约定（见 {@link OpsAuditRecorder}）：写入是旁路，失败只记日志；
 * 且 {@code detail_json} 里放结构信息（如 {@code {"count":3}}），<b>不放用户健康内容正文</b>——
 * 否则审计表会变成病历的第二份副本，反而扩大了敏感数据的暴露面。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_audit_log")
public class OpsAuditLogEntity extends AppendOnlyEntity {

    /** 运营账号 ID；列 NOT NULL，拿不到时写 0（与 created_by 同口径）。 */
    private Long opsId;

    /** 操作人账号冗余；列 NOT NULL DEFAULT ''，拿不到就写空串（绝不为它去查库，见 Recorder 注释）。 */
    private String operatorName;

    /** 操作时角色编码，可空。 */
    private String roleCode;

    /** 模块：ai / dashboard / news / banner / media / food / feedback / export / user / archive / system / push。 */
    private String moduleCode;

    /** 动作编码，如 ai_memory_delete。 */
    private String actionCode;

    /** 动作中文，如"删除用户AI长期记忆"。 */
    private String actionName;

    /** 目标类型，如 user / feedback / prompt。 */
    private String targetType;

    /** 目标主键；列 NOT NULL DEFAULT 0，无具体目标时写 0（不是 null）。 */
    private Long targetId;

    /** 详情 JSON（varchar(2000)，超长由 Recorder 截断）；只放结构信息，不放健康内容正文。 */
    private String detailJson;

    /** 请求 URI，可空（非 Web 线程内为 null）。 */
    private String requestUri;

    /** 请求方法，可空。 */
    private String requestMethod;

    /** 客户端 IP，可空。 */
    private String clientIp;

    /** 耗时毫秒，可空（本 Recorder 不测耗时，留给将来有需要的调用方）。 */
    private Integer costMs;

    /** 业务返回码，可空。 */
    private Integer resultCode;
}
