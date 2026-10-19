package com.threadfit.glucopilot.common.audit;

import com.threadfit.glucopilot.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营操作审计查询（契约 3.31），管理端"AI 审计日志"页的数据源。
 *
 * <h2>为什么只暴露一个 GET</h2>
 * 审计表只追加（见 {@link OpsAuditLogEntity}），接口层也就不提供任何写/改/删入口：
 * 「误改审计」在别处至少需要绕过约定，在这里连路由都没有。恢复一条被删的审计记录的唯一正路是
 * 从备份里捞，而不是让人在页面上点两下。
 *
 * <h2>权限为什么是 {@code ai:audit:view}</h2>
 * 与运营菜单（V24 的 2032）用同一个权限串。审计里会出现"谁在什么时候看了哪个用户的数据"，
 * 这本身就是敏感信息；`ai:audit:view` 只授予超管与运营，且与 `ai:memory:view`（看记忆正文）分开——
 * 能看操作台账不该顺带等于能看健康内容。
 *
 * <h2>筛选条件全部精确匹配</h2>
 * 四个条件都是等值（模块/动作/目标类型/目标主键），没有关键词模糊搜索：
 * 模糊匹配 {@code detail_json} 会把审计表变成一个可按内容探测的接口，而精确匹配已经覆盖了
 * 复盘的真实需求（"这个用户的记忆被谁删过"→ moduleCode=ai + targetType=user + targetId=18）。
 */
@RestController
@RequestMapping("/admin/v1/ops-audit-logs")
@RequiredArgsConstructor
public class AdminOpsAuditLogController {

    private final AdminOpsAuditLogService adminOpsAuditLogService;

    /**
     * 3.31 条件分页查询。
     * <p>
     * {@code pageSize} 缺省 20、上限 100，越界<b>夹紧</b>不报错（与 4.14 / 5.3 同口径）。
     * {@code targetId} 按 Long 接收：传了非数字会由全局异常处理器转成 400 的干净响应，
     * 而不是把这些字符当 0 处理——那会静默返回"目标 0 的记录"，看起来像查到了东西。
     */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('ai:audit:view')")
    public ApiResponse<OpsAuditLogDtos.AuditLogList> list(
            @RequestParam(required = false) String moduleCode,
            @RequestParam(required = false) String actionCode,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.ok(adminOpsAuditLogService.list(moduleCode, actionCode, targetType, targetId,
                pageNum, pageSize));
    }
}
