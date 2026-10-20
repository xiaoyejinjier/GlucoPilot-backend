package com.threadfit.glucopilot.common.audit;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计查询页（契约 3.31）的出参。
 *
 * <h2>为什么字段几乎是整表照搬，而不是只给"运营想看的几列"</h2>
 * 审计查询只有一个用途：<b>事后复盘</b>。复盘时最有价值的往往不是被精心挑选的列，而是"当时到底发生了什么"
 * ——请求 URI、方法、IP、耗时、结果码、detail_json 原样给出，运营才能自己还原现场；
 * 反过来，任何"为了让表格好看"而裁掉的字段，都会在下一次排查时变成一次新的后端改动。
 * 因此这里 16 个字段与表列一一对应，前端只决定"默认显示哪几列"。
 *
 * <h2>为什么没有分页回显（pageNum / pageSize / pages）</h2>
 * 契约 3.31 的出参是 {@code data = { records, total }}。管理端的分页组件自己持有页码与页大小，
 * 回显字段一旦与请求参数不一致，页面就会出现"翻到第 2 页却又跳回第 1 页"这类只在特定时序下复现的错。
 * 只给 {@code total} 让它算页数，是最不容易出错的最小契约。
 */
public final class OpsAuditLogDtos {

    private OpsAuditLogDtos() {
    }

    /** 一条审计记录（字段名与表列逐字对应，前端契约已按此写好）。 */
    @Data
    @Builder
    public static class AuditLogRow {

        /** 主键（Long 由全局 Jackson 配置序列化为字符串，防前端精度丢失）。 */
        private Long id;

        /** 操作人运营账号 ID；无操作人上下文时为 0。 */
        private Long opsId;

        /** 操作人姓名冗余；写入时不去查库补全，因此可能是空串。 */
        private String operatorName;

        /** 操作时角色编码，可空。 */
        private String roleCode;

        /** 模块编码，如 {@code ai}。 */
        private String moduleCode;

        /** 动作编码，如 {@code ai_memory_delete}。 */
        private String actionCode;

        /** 动作中文名，如"删除用户AI长期记忆"。 */
        private String actionName;

        /** 目标类型，如 {@code user}。 */
        private String targetType;

        /** 目标主键；无具体目标时为 0。 */
        private Long targetId;

        /** 详情 JSON <b>原样字符串</b>：查询页负责美化，后端不解析也不再拼装（解析失败也不能报错）。 */
        private String detailJson;

        /** 请求 URI，异步/非 Web 线程写入时为 null。 */
        private String requestUri;

        /** 请求方法，可空。 */
        private String requestMethod;

        /** 客户端 IP，可空。 */
        private String clientIp;

        /** 耗时毫秒，可空。 */
        private Integer costMs;

        /** 业务返回码，可空。 */
        private Integer resultCode;

        /** 发生时间（列只有 gmt_create：审计表只追加）。 */
        private LocalDateTime gmtCreate;
    }

    /** 审计列表。 */
    @Data
    @Builder
    public static class AuditLogList {

        private List<AuditLogRow> records;

        /** 满足条件的<b>总条数</b>（不是本页条数）：前端据此判断还有没有下一页。 */
        private Long total;
    }
}
