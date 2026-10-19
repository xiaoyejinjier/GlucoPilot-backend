package com.threadfit.glucopilot.common.audit;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 审计查询页（契约 3.31）：只读的运营操作留痕检索。
 *
 * <h2>为什么这个接口<b>自己不写审计</b></h2>
 * 其它敏感读接口（例如 4.14 查询用户长期记忆）都会落一条"谁看了谁的数据"，
 * 这里刻意不落，理由是它会把审计表变成一个自激的表：
 * <ul>
 *   <li>查一次审计 → 多一条审计 → 下次查又多一条…… 条数随"有人看"线性增长；</li>
 *   <li>更要紧的是<b>它会污染被查的东西</b>：按 {@code moduleCode=ai} 过滤时，点几次页面就会刷出一屏
 *       "查询审计日志"，真正的业务动作被自己的查看记录挤到下一页去了；</li>
 *   <li>而"谁在查审计"这件事并不因此失去痕迹：管理端请求本身有 {@code sys_oper_log} 与登录日志，
 *       审计查询是<b>运营内部的只读观察</b>，不是对用户敏感数据的访问。</li>
 * </ul>
 * 换句话说：审计要记录的是"动了用户数据"的操作；看一张操作台账，不等于动了用户数据。
 *
 * <h2>分页参数为什么"夹紧"而不是报 400</h2>
 * 与 4.14/5.3 同口径：pageSize 缺省 20、上限 100，null/0/负数按缺省值处理，超过 100 夹到 100。
 * 运营页会把输入框原样带上，参数不合法就整页打不开属于把校验的锅甩给用户。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOpsAuditLogService {

    /** 与 4.14 / 5.3 同一口径：缺省 20、上限 100。 */
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final OpsAuditLogMapper opsAuditLogMapper;

    /**
     * 条件分页查询。
     *
     * @param moduleCode 模块编码，空 = 不限
     * @param actionCode 动作编码，空 = 不限
     * @param targetType 目标类型，空 = 不限
     * @param targetId   目标主键，null = 不限
     * @param pageNum    页码（1 起），null/&lt;1 按 1
     * @param pageSize   页大小，null/&lt;1 按 20，&gt;100 夹到 100
     */
    public OpsAuditLogDtos.AuditLogList list(String moduleCode, String actionCode, String targetType,
                                             Long targetId, Integer pageNum, Integer pageSize) {
        int size = clampSize(pageSize);
        int page = pageNum == null || pageNum <= 0 ? 1 : pageNum;

        OpsAuditLogMapper.Filter filter = new OpsAuditLogMapper.Filter(
                null, moduleCode, actionCode, targetType, targetId, null, null, null);
        Page<OpsAuditLogEntity> result = opsAuditLogMapper.selectPage(new Page<>(page, size), filter);

        List<OpsAuditLogDtos.AuditLogRow> records = new ArrayList<>(
                result.getRecords() == null ? 0 : result.getRecords().size());
        if (result.getRecords() != null) {
            for (OpsAuditLogEntity row : result.getRecords()) {
                records.add(toRow(row));
            }
        }
        // DEBUG 而非 INFO：这是运营高频点击的只读页，逐次打 INFO 会把日志刷满，
        // 真正需要排查时按 requestId 找即可（筛选条件原样回显在请求里）
        log.debug("审计查询 page={} size={} module={} action={} target={}:{} total={}",
                page, size, moduleCode, actionCode, targetType, targetId, result.getTotal());
        return OpsAuditLogDtos.AuditLogList.builder()
                .records(records)
                .total(result.getTotal())
                .build();
    }

    /** 页大小夹紧：缺省 20、上限 100，不合法按缺省值（与 4.14 同一个判据）。 */
    static int clampSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private static OpsAuditLogDtos.AuditLogRow toRow(OpsAuditLogEntity row) {
        return OpsAuditLogDtos.AuditLogRow.builder()
                .id(row.getId())
                .opsId(row.getOpsId())
                .operatorName(row.getOperatorName())
                .roleCode(row.getRoleCode())
                .moduleCode(row.getModuleCode())
                .actionCode(row.getActionCode())
                .actionName(row.getActionName())
                .targetType(row.getTargetType())
                .targetId(row.getTargetId())
                .detailJson(row.getDetailJson())
                .requestUri(row.getRequestUri())
                .requestMethod(row.getRequestMethod())
                .clientIp(row.getClientIp())
                .costMs(row.getCostMs())
                .resultCode(row.getResultCode())
                .gmtCreate(row.getGmtCreate())
                .build();
    }
}
