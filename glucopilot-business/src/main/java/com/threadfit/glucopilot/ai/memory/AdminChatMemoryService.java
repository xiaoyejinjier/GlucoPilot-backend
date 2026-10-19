package com.threadfit.glucopilot.ai.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.common.audit.OpsAuditRecorder;
import com.threadfit.glucopilot.common.context.RequestContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理端"用户 AI 长期记忆"（契约 4.14 查询 / 4.15 删除）。
 *
 * <h2>为什么读写两个动作都必须留痕</h2>
 * 长期记忆里是用户的健康状况与偏好，运营能看到它本身就是一次<b>敏感数据访问</b>——
 * 只记"谁删了"而不记"谁看了"，等于默许"看完不留痕"，而泄露恰恰主要发生在"看"这一侧。
 * 因此查询与删除各写一条 ops_audit_log（模块 {@code ai}）。
 *
 * <h2>审计里写什么、绝对不写什么</h2>
 * {@code detail_json} 只放<b>结构信息</b>（如 {@code {"count":3}}），<b>绝不放 memory_text 正文</b>：
 * 审计表的定位是"谁在什么时候动了哪条数据"，一旦把健康内容抄进去，它就成了用户隐私的第二份副本——
 * 用户要求删除记忆之后，正文仍留在审计表里，删除权当场失效，而且审计表的访问面比记忆表更宽
 * （查询页、导出、排障日志都可能碰它）。要定位具体内容，靠 {@code target_id}（用户 ID）+ 时间点
 * 去业务侧查即可，不必在这里留副本。
 *
 * <h2>为什么"用户没有记忆"不是错误</h2>
 * 4.14 的语义是"查这个用户的记忆"，用户不存在与用户没有记忆在运营侧是同一个结论（空列表），
 * 强行区分只会逼运营去另一个接口确认用户是否存在；4.15 同理返回删除 0 条而不是 404，
 * 让"重复点击删除/用户本来就没记忆"成为幂等成功——这类操作报错只会让人反复重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminChatMemoryService {

    /** 与接口 5.3（会话列表）同一口径：pageSize 缺省 20、上限 100，超出夹到上限而不是报错。 */
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private static final String MODULE_CODE = "ai";
    private static final String TARGET_TYPE_USER = "user";

    private final ChatUserMemoryMapper memoryMapper;
    private final OpsAuditRecorder auditRecorder;

    // ------------------------------------------------------------------ 4.14 查询

    /**
     * 某用户的长期记忆列表。
     * <p>
     * 「只查未删除」由实体的 {@code @TableLogic} 保证（框架自动追加 {@code is_deleted = 0}），
     * 这里不再手写一遍：两处条件重复时，将来任何一处改动都会静默产生"删了却还查得到"。
     * <p>
     * 排序 {@code updated_at DESC, id DESC}：最近更正的记忆在最前（用户最可能关心的就是刚被记错的那条），
     * id 兜底保证同毫秒多行时分页稳定，否则翻页会重复或漏行。
     * <p>
     * 分页参数缺省/越界一律<b>夹紧</b>（null、0、负数按缺省值；超过 100 按 100），
     * 而不是报 400：运营页会把输入框原样带上，参数不合法就让整页打不开，属于把后端校验的锅甩给用户。
     */
    public AdminChatMemoryDtos.MemoryList list(Long userId, Integer pageNum, Integer pageSize) {
        if (userId == null) {
            // 路径变量理论上不会为空；真为空时按"没有记忆"返回，避免用 null 去查全表
            return AdminChatMemoryDtos.MemoryList.builder().records(List.of()).total(0L).build();
        }
        int size = pageSize == null || pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        int page = pageNum == null || pageNum <= 0 ? 1 : pageNum;

        LambdaQueryWrapper<ChatUserMemoryEntity> wrapper = new LambdaQueryWrapper<ChatUserMemoryEntity>()
                .eq(ChatUserMemoryEntity::getUserId, userId)
                .orderByDesc(ChatUserMemoryEntity::getGmtModified)
                .orderByDesc(ChatUserMemoryEntity::getId);
        Page<ChatUserMemoryEntity> result = memoryMapper.selectPage(new Page<>(page, size), wrapper);

        List<AdminChatMemoryDtos.MemoryRow> records = new ArrayList<>(result.getRecords().size());
        for (ChatUserMemoryEntity row : result.getRecords()) {
            records.add(toRow(row));
        }
        // 记 total 而不是本页条数：合规关心的是"这个用户到底被记了多少条"，翻到第几页不影响这个事实
        audit("ai_memory_view", "查询用户AI长期记忆", userId, "{\"count\":" + result.getTotal() + "}");
        return AdminChatMemoryDtos.MemoryList.builder()
                .records(records)
                .total(result.getTotal())
                .build();
    }

    // ------------------------------------------------------------------ 4.15 删除

    /**
     * 逻辑删除某用户的全部长期记忆（合规删除权）。
     * <p>
     * 一条 UPDATE 完成（见 {@link ChatUserMemoryMapper#logicDeleteAllByUser}）：删全部时不逐条处理，
     * 既避免长事务，也避免"删到一半失败"留下部分删除的状态。
     * <p>
     * 返回真实删除条数（0 = 本来就没有记忆），并照常写审计：删除动作的价值不在"删了几条"，
     * 而在"谁在什么时候行使了删除权"。
     */
    @Transactional
    public AdminChatMemoryDtos.DeleteResult deleteAll(Long userId) {
        if (userId == null) {
            return AdminChatMemoryDtos.DeleteResult.builder().deleted(0).build();
        }
        int deleted = memoryMapper.logicDeleteAllByUser(userId, RequestContext.getOpsId());
        log.info("管理端删除用户长期记忆 userId={} 条数={} 操作人={}", userId, deleted, RequestContext.getOpsId());
        audit("ai_memory_delete", "删除用户AI长期记忆", userId, "{\"count\":" + deleted + "}");
        return AdminChatMemoryDtos.DeleteResult.builder().deleted(deleted).build();
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 落一条审计：<b>再兜一层异常</b>。
     * <p>
     * {@link OpsAuditRecorder} 自己已经吞掉写库异常，这里为什么还要 catch：审计失败绝不能让业务失败
     * 这条约定不能只靠"调用方记得实现好"来保证——将来换实现、加装饰器、或者在测试里替换成会抛异常的桩，
     * 都会把"旁路"变成"主链路"。在<b>业务侧调用点</b>再挡一次，是让这条约定不依赖下游实现。
     */
    private void audit(String actionCode, String actionName, Long userId, String detailJson) {
        try {
            auditRecorder.record(MODULE_CODE, actionCode, actionName, TARGET_TYPE_USER, userId, detailJson);
        } catch (RuntimeException e) {
            log.warn("写审计失败（不影响本次操作）action={} userId={}", actionCode, userId, e);
        }
    }

    private static AdminChatMemoryDtos.MemoryRow toRow(ChatUserMemoryEntity row) {
        return AdminChatMemoryDtos.MemoryRow.builder()
                .id(row.getId())
                .memoryKey(row.getMemoryKey())
                .memoryText(row.getMemoryText())
                .confidence(row.getConfidence())
                .sourceSessionId(row.getSourceSessionId())
                .updatedAt(row.getGmtModified())
                .isAuto(isAuto(row.getCreatedBy()))
                .build();
    }

    /** 自动抽取的启发式判据，见 DTO 注释（仅展示用，不作权限判据）。 */
    private static Boolean isAuto(Long createdBy) {
        return createdBy == null || createdBy.longValue() == 0L;
    }
}
