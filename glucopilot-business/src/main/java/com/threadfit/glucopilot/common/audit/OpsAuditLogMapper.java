package com.threadfit.glucopilot.common.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计日志读写（ops_audit_log）。
 *
 * <h2>为什么只有两条查询，而且都不写 SQL</h2>
 * <ul>
 *   <li>{@link #selectPage} 是给后台"审计日志查询页"（契约 3.31）用的：条件全是等值与时间区间，
 *       交给 MyBatis-Plus 的 {@code LambdaQueryWrapper} 拼，字段名由 lambda 推导——
 *       手写 SQL 时最容易发生的"改了实体忘了改 SQL"在这里不存在。分页交给调用方
 *       （{@code selectPage(Page, Wrapper)} 或 PageHelper），本方法只负责"条件与排序"；</li>
 *   <li>{@link #selectByTarget} 是"这条记录被谁动过"的回溯入口，按 {@code (module_code, target_type, target_id)}
 *       恰好命中 {@code idx_target} 索引；写成注解 SQL 是因为它必须带 {@code LIMIT}（没有上限的回溯查询
 *       迟早会变成全表扫描）。</li>
 * </ul>
 * 表本身只追加，因此这里<b>刻意不提供任何 update / delete 封装</b>：想改审计只能绕过本接口，
 * 这让"误改"从"顺手"变成"刻意"。
 */
public interface OpsAuditLogMapper extends BaseMapper<OpsAuditLogEntity> {

    /**
     * 查询页的过滤条件；字段全部可空，为空即不加该条件。
     *
     * @param opsId      操作人
     * @param moduleCode 模块
     * @param actionCode 动作
     * @param targetType 目标类型
     * @param targetId   目标主键
     * @param from       起始时间（含，按 gmt_create）
     * @param to         结束时间（含，按 gmt_create）
     * @param keyword    关键词，模糊匹配操作人与动作名
     */
    record Filter(Long opsId, String moduleCode, String actionCode, String targetType, Long targetId,
                  LocalDateTime from, LocalDateTime to, String keyword) {
    }

    /**
     * 条件查询（不分页）：条件与排序在这里，LIMIT 由调用方决定。
     * <p>
     * 固定按 {@code id DESC} 排序：{@code gmt_create} 是毫秒精度，同一毫秒内的多条审计
     * （例如一次请求里连写两条）单靠时间排序结果不稳定，翻页时会重复或漏行；id 自增且单调，
     * 与插入顺序一致，用作兜底才让分页可复现。
     */
    default List<OpsAuditLogEntity> selectPage(Filter filter) {
        return selectList(condition(filter));
    }

    /**
     * 同条件的<b>分页</b>查询（供契约 3.31 的审计查询页真正翻页用）。
     * <p>
     * 为什么不在这里自己拼 {@code LIMIT}：分页插件会额外发一条 {@code COUNT}，而 {@code LIMIT}
     * 与总数必须是同一套条件、同一时刻算出来的，否则「第 3 页说有 100 条、翻过去却是空的」。
     * 交给 {@code PaginationInnerInterceptor} 处理，条件只写在这一处（{@link #condition}）。
     * <p>
     * 这是对 {@code BaseMapper#selectPage(IPage, Wrapper)} 的<b>重载</b>（形参不同），
     * 内置方法仍然可用——两者共存这一点在 {@code OpsAuditRecorderTest} 里被真库钉住过。
     */
    default Page<OpsAuditLogEntity> selectPage(Page<OpsAuditLogEntity> page, Filter filter) {
        return selectPage(page, condition(filter));
    }

    /**
     * 条件与排序的唯一构造点。
     * <p>
     * 抽成一处是因为"分页"与"不分页"两条路径必须用<b>完全一样</b>的条件：分开写时，
     * 将来只改一处就会出现「列表里筛得到、翻页总数对不上」这种只在生产数据量下才暴露的错。
     */
    private LambdaQueryWrapper<OpsAuditLogEntity> condition(Filter filter) {
        Filter condition = filter == null ? new Filter(null, null, null, null, null, null, null, null) : filter;
        LambdaQueryWrapper<OpsAuditLogEntity> wrapper = new LambdaQueryWrapper<OpsAuditLogEntity>()
                .eq(condition.opsId() != null, OpsAuditLogEntity::getOpsId, condition.opsId())
                .eq(StringUtils.hasText(condition.moduleCode()), OpsAuditLogEntity::getModuleCode,
                        condition.moduleCode())
                .eq(StringUtils.hasText(condition.actionCode()), OpsAuditLogEntity::getActionCode,
                        condition.actionCode())
                .eq(StringUtils.hasText(condition.targetType()), OpsAuditLogEntity::getTargetType,
                        condition.targetType())
                .eq(condition.targetId() != null, OpsAuditLogEntity::getTargetId, condition.targetId())
                .ge(condition.from() != null, OpsAuditLogEntity::getGmtCreate, condition.from())
                .le(condition.to() != null, OpsAuditLogEntity::getGmtCreate, condition.to());
        if (StringUtils.hasText(condition.keyword())) {
            String keyword = condition.keyword().trim();
            // 关键词只搜"操作人/动作名"这两个纯运营侧字段：detail_json 里可能含业务细节，
            // 拿它做模糊匹配既用不上索引，也等于给了一个探测内容的接口。
            wrapper.and(w -> w.like(OpsAuditLogEntity::getOperatorName, keyword)
                    .or().like(OpsAuditLogEntity::getActionName, keyword));
        }
        return wrapper.orderByDesc(OpsAuditLogEntity::getId);
    }

    /**
     * 某个目标（如某用户）最近的操作留痕，倒序。
     * <p>
     * {@code LIMIT} 由调用方给：合规排查通常只看"最近 N 条"，而目标可能是高频对象
     * （一个被反复查看的用户详情），无上限查询会给慢查询埋雷。
     */
    @Select("SELECT * FROM ops_audit_log WHERE module_code = #{moduleCode} AND target_type = #{targetType} "
            + "AND target_id = #{targetId} ORDER BY id DESC LIMIT #{limit}")
    List<OpsAuditLogEntity> selectByTarget(@Param("moduleCode") String moduleCode,
                                           @Param("targetType") String targetType,
                                           @Param("targetId") Long targetId,
                                           @Param("limit") Integer limit);
}
