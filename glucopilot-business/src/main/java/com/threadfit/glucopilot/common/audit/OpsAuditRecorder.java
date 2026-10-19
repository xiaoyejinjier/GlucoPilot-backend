package com.threadfit.glucopilot.common.audit;

import com.threadfit.glucopilot.common.context.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 运营操作审计的写入入口（ops_audit_log）。
 *
 * <h2>为什么审计必须"吞异常"</h2>
 * 审计是<b>旁路</b>：它的作用是事后追责，而不是让当前动作成功。反过来，如果因为"写审计失败"
 * 让一次本来成功的删除/查询返回 500，就成了用次要目标否决主要目标——运营会看到操作失败，
 * 却又不知道业务到底做没做（这正是最难排查的状态）。因此本类所有路径都只 WARN，
 * <b>绝不向上抛异常</b>；缺字段也一律降级为占位值，而不是报错。
 * <p>
 * 同一原则的既有实现见 {@code ToolCallTraceRecorder}（工具调用元数据登记同样只 WARN）。
 *
 * <h2>为什么不查库补全 operator_name / role_code</h2>
 * 冗余操作人姓名只是让查询页少一次 join。为此在写审计的主链路上多查一次账号表，
 * 等于给"审计能不能写成功"增加了一个新的失败点：账号表暂时不可用就会让留痕整体丢失。
 * 因此宁可写空串（列是 NOT NULL DEFAULT ''），把姓名交给查询页按 ops_id 关联。
 *
 * <h2>detail_json 的两条纪律</h2>
 * <ol>
 *   <li><b>截断到列宽</b>：列是 varchar(2000)，严格模式下超长直接报错——而报错会被本类吞掉，
 *       表现成"审计莫名其妙缺了一条"，比显式截断难查得多；</li>
 *   <li><b>只放结构信息</b>：如 {@code {"count":3}}，不放用户健康内容正文（记忆原文、问诊原文等）。
 *       审计表一旦成为第二份内容副本，删除权与最小化原则就同时失效了。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsAuditRecorder {

    /** 与 DDL 的 {@code detail_json VARCHAR(2000)} 对齐；留 0 余量，因为截断是按字符数算的。 */
    public static final int DETAIL_JSON_MAX_LENGTH = 2000;

    /** 目标主键缺省值：列 NOT NULL DEFAULT 0，用 0 表达"无具体目标"。 */
    private static final long NO_TARGET = 0L;

    private final OpsAuditLogMapper opsAuditLogMapper;

    /**
     * 记一条运营操作审计。
     *
     * @param moduleCode 模块编码，如 {@code ai}
     * @param actionCode 动作编码，如 {@code ai_memory_delete}
     * @param actionName 动作中文名（查询页直接展示，不再靠前端维护映射）
     * @param targetType 目标类型，如 {@code user}
     * @param targetId   目标主键，可为 null（写 0）
     * @param detailJson 详情 JSON，只放结构信息；超长会被截断，可为 null
     */
    public void record(String moduleCode, String actionCode, String actionName,
                       String targetType, Long targetId, String detailJson) {
        try {
            Long opsId = RequestContext.getOpsId();
            OpsAuditLogEntity row = new OpsAuditLogEntity();
            row.setOpsId(opsId == null ? NO_TARGET : opsId);
            // created_by 由 MetaObjectHandler 统一填充，但它取的是"ops 优先、其次 C 端用户"；
            // 这里显式写死 opsId，避免 C 端线程调用时把 user_id 混进运营审计（严格填充只在字段为 null 时生效）。
            row.setCreatedBy(opsId == null ? NO_TARGET : opsId);
            row.setOperatorName("");
            row.setRoleCode(null);
            row.setModuleCode(blankToEmpty(moduleCode));
            row.setActionCode(blankToEmpty(actionCode));
            row.setActionName(blankToEmpty(actionName));
            row.setTargetType(blankToEmpty(targetType));
            row.setTargetId(targetId == null ? NO_TARGET : targetId);
            row.setDetailJson(truncateDetail(detailJson));
            row.setClientIp(RequestContext.getClientIp());
            HttpServletRequest request = currentRequest();
            row.setRequestUri(request == null ? null : truncate(request.getRequestURI(), 255));
            row.setRequestMethod(request == null ? null : truncate(request.getMethod(), 10));
            opsAuditLogMapper.insert(row);
        } catch (RuntimeException e) {
            log.warn("审计写入失败（不影响业务动作）：module={} action={} target={}:{}",
                    moduleCode, actionCode, targetType, targetId, e);
        }
    }

    /**
     * 当前请求（非 Web 线程返回 null）。
     * <p>
     * {@link RequestContext} 只存了 clientIp，没有 URI 与方法，因此从 Spring 的请求上下文取；
     * 取不到（异步线程、单测、定时任务）就留 null——审计行的这两列本来就允许为空，
     * 为它们引入 RequestContext 的新字段反而会让"上下文由 Filter 写入"这条约定变复杂。
     */
    private static HttpServletRequest currentRequest() {
        try {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** detail_json 超长截断到列宽：见类注释第 1 条纪律。 */
    private static String truncateDetail(String detailJson) {
        if (detailJson == null || detailJson.length() <= DETAIL_JSON_MAX_LENGTH) {
            return detailJson;
        }
        log.warn("审计 detail_json 超长已截断：原长度={} 上限={}（该字段只应放结构信息）",
                detailJson.length(), DETAIL_JSON_MAX_LENGTH);
        return detailJson.substring(0, DETAIL_JSON_MAX_LENGTH);
    }

    /**
     * NOT NULL 的编码列一律不留 null：模块/动作/目标类型在 DDL 上是 NOT NULL，
     * 传 null 会被严格模式拒绝（然后在上面被吞掉，变成"审计悄悄少一条"）。
     */
    private static String blankToEmpty(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
