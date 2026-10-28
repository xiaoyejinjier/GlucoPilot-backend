package com.threadfit.glucopilot.ai.business.chat.service;

import com.threadfit.glucopilot.ai.business.chat.dto.ChatDtos;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;

/**
 * 会话生命周期契约（2.23 取/建会话、5.3 列表、5.4 删除、置顶、以及"能不能继续写"的状态校验）。
 *
 * <h2>状态语义（契约只写了 0/1/2 三个值，区别在这里定死）</h2>
 * <ul>
 *   <li><b>1 进行中</b>：每用户至多一条（由唯一键 uk_user_active 保证），只有它能继续发消息；</li>
 *   <li><b>2 已结束</b>：用户主动"新建对话"时把旧的那条置为它；</li>
 *   <li><b>0 归档</b>：太久没说话（ai.chat.archive-idle-days）由列表接口<b>惰性</b>标记。</li>
 * </ul>
 *
 * <h2>并发与顺序</h2>
 * 「每用户至多一条进行中会话」由数据库唯一键保证，而不是靠"先查再建"；
 * 因此 openNew 必须先结束旧的再插入新的（同一事务），撞 1062 时回查并发方建好的那条。
 */
public interface ChatSessionService {

    /** 2.23 取进行中会话；没有就建一个（并发撞唯一键时收敛到同一条）。 */
    ChatSession getOrCreate(Long userId, Integer sessionType);

    /** 2.23 forceNew：先结束旧的进行中会话，再新建（同一事务）。 */
    ChatSession openNew(Long userId, Integer sessionType);

    /** 5.3 会话列表（含惰性归档与最后一条消息预览，无 N+1）。 */
    ChatDtos.SessionListResponse list(Long userId, Integer sessionStatus, Integer pageNum, Integer pageSize);

    /** 5.4 删除会话：会话与消息同事务逻辑删除；重复删除幂等成功。 */
    void delete(Long userId, Long sessionId);

    /** 置顶/取消置顶。 */
    void setPinned(Long userId, Long sessionId, boolean pinned);

    /** 校验会话存在且属于当前用户（不属于按 404，不返回 403 避免泄露"该会话存在"）。 */
    ChatSession requireOwned(Long userId, Long sessionId);

    /** 首条消息到达时用消息内容生成标题（已是自定义标题则不动）。 */
    void renameIfDefault(Long sessionId, String contentText);

    /**
     * 校验"这个会话还能继续写"（2.24 / 2.25 用）。
     * <p>
     * 已结束/已归档的会话必须拒绝：否则消息进了老会话、/current 却指着新会话，
     * 或者被归档的会话因为一条新消息"活"过来但状态仍是归档。
     */
    ChatSession requireActive(Long userId, Long sessionId);

    /** 实体 → 2.23 出参。 */
    ChatDtos.SessionResponse toResponse(ChatSession session);

    /**
     * 账号注销：物理删除该用户全部对话数据（消息 / 分片 / 工具调用 / 会话）。
     * <p>
     * 独立事务、幂等；由注销 worker 经 user 模块编排调用，不经过 controller。
     */
    void purgeAccountData(Long userId);
}