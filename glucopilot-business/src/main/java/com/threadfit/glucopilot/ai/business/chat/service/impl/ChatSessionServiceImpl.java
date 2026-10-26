package com.threadfit.glucopilot.ai.business.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.ai.business.chat.dto.ChatDtos;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.core.AiChatClientRegistry;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.memory.service.UserChatMemoryService;
import com.threadfit.glucopilot.ai.prompt.service.AiPromptTemplateService;
import com.threadfit.glucopilot.ai.support.SqlDuplicateKeys;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.threadfit.glucopilot.ai.memory.service.AdminChatMemoryService;
import com.threadfit.glucopilot.ai.business.chat.service.ChatSessionService;

/**
 * 会话生命周期：2.23 取/建会话（含"新建"）、5.3 列表、5.4 删除、置顶、以及"能不能继续写"的状态校验。
 * <p>
 * <b>状态语义</b>（契约只写了 0/1/2 三个值，没定义区别，这里定死并在文档里写清）：
 * <ul>
 *   <li>{@code 1 进行中}：每用户至多一条（由 {@code uk_user_active} 保证），只有它能继续发消息；</li>
 *   <li>{@code 2 已结束}：用户主动"新建对话"时把旧的那条置为它，历史会话默认按它展示；</li>
 *   <li>{@code 0 归档}：太久没说话（{@code ai.chat.archive-idle-days}）由列表接口<b>惰性</b>标记，
 *       列表默认折叠，语义是"不用再出现在最近列表里了"。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionServiceImpl implements ChatSessionService {

    /** 会话默认标题 */
    public static final String DEFAULT_TITLE = "新对话";
    /** 标题截取长度 */
    private static final int TITLE_MAX = 20;
    /** 状态：0 归档 / 1 进行中 / 2 已结束 */
    private static final int STATUS_ARCHIVED = 0;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_ENDED = 2;
    /** 列表页默认/最大条数 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final com.threadfit.glucopilot.ai.business.chat.mapper.ChatContextMapper chatContextMapper;
    private final com.threadfit.glucopilot.ai.business.chat.mapper.ChatToolCallMapper chatToolCallMapper;
    private final AIProperties aiProperties;
    private final AiChatClientRegistry chatClientRegistry;
    private final AiPromptTemplateService promptTemplateService;

    /** ③ 期：会话结束时的长期记忆抽取（可选依赖语义：抽不到就不记，不影响新建会话）。 */
    private final UserChatMemoryService userChatMemoryService;

    /**
     * 2.23 取进行中会话；没有就建一个。
     * <p>
     * <b>并发下"每用户至多一条进行中会话"由唯一键 {@code uk_user_active} 保证</b>
     * （建在生成列 {@code active_user_id} 上，见 V11 迁移），而不是由上面的
     * {@code selectActiveByUserId} 保证——那是普通快照读，两个并发请求都会查不到，
     * 然后各建一条（实测可复现）。
     * <p>
     * 落败的一方在这里捕获 1062 并回查并发方刚建好的那条会话，因此<b>两个请求拿到同一个 sessionId</b>，
     * 不会出现"用户的消息进了 A 会话、助手回复进了 B 会话"这种历史分裂。
     * <p>
     * 注意不要试图用「包一层事务再查一次」来修：MySQL 默认 REPEATABLE READ，
     * 事务内再查读到的仍是第一次读的快照，并发方提交之后依然看不见（已实测）。
     */
    public ChatSession getOrCreate(Long userId, Integer sessionType) {
        ChatSession existing = chatSessionMapper.selectActiveByUserId(userId);
        if (existing != null) {
            return existing;
        }
        return insertNewSession(userId, sessionType);
    }

    /**
     * 新建会话（2.23 的 {@code forceNew=true}，以及"上次会话太久没说话"时由惰性归档让路）。
     * <p>
     * <b>必须先结束旧的进行中会话，且在同一个事务里</b>：{@code uk_user_active} 建在生成列
     * {@code active_user_id} 上（只有 {@code session_status = 1 AND is_deleted = 0} 时才等于 user_id），
     * 不结束旧的就插新的必然撞 1062。这与 {@link #getOrCreate} 的错误语义<b>不同</b>：
     * 那里撞 1062 是"并发建会话"，复用它是对的；这里撞 1062 只能是并发新建，回查返回并发方建的那条。
     * <p>
     * <b>③ 期：会话结束是长期记忆的主抽取时机。</b>顺序是"先取旧会话 → 结束 → 建新 → 再抽取"：
     * 抽取要靠旧会话的 ID 读它的消息，所以必须在
     * {@link ChatSessionMapper#endActiveSessions} <b>之前</b>把 ID 拿到手；而放在
     * {@code insertNewSession} 之后调用，是为了"这次新建失败了就别白花一次模型调用"。
     * <p>
     * 抽取是<b>异步且失败只记日志</b>的（见 {@code UserChatMemoryService#extractAsync}）：
     * 用户点"新对话"的本意是"马上开始说下一句"，绝不该为此等一次模型调用；
     * 抽不到记忆只是增强项缺失，而新建会话失败是功能性故障，两者不能互相牵连。
     * {@code previous == null}（本来就没有进行中会话）时不调用——没有旧会话就没有可抽的东西。
     */
    @Transactional
    public ChatSession openNew(Long userId, Integer sessionType) {
        // 旧会话 ID 必须在结束之前读：endActiveSessions 之后它就查不到了（这正是它要干的事）
        ChatSession previous = chatSessionMapper.selectActiveByUserId(userId);
        int ended = chatSessionMapper.endActiveSessions(userId);
        log.info("新建会话：先结束旧的进行中会话 userId={} ended={}", userId, ended);
        ChatSession created = insertNewSession(userId, sessionType);
        if (previous != null) {
            // 只在真的结束了旧会话时才抽：并发新建时可能一条都没结束（ended=0），此时 previous 也已为 null
            extractMemoryQuietly(userId, previous.getId());
        }
        return created;
    }

    /**
     * 账号注销：物理删除该用户的全部对话数据。
     *
     * <p>顺序按"子表先、父表后"：会话是归属锚点，先删会话会让其余三张表失去定位依据。
     * 工具调用放最后但仍在会话之前——它的 {@code result_digest} 可能带健康事实，
     * 不能因为"只是元数据"而漏删。
     */
    @Override
    @Transactional
    public void purgeAccountData(Long userId) {
        chatMessageMapper.purgeByUserId(userId);
        chatContextMapper.purgeByUserId(userId);
        chatToolCallMapper.purgeByUserId(userId);
        chatSessionMapper.purgeByUserId(userId);
    }

    /**
     * 会话结束时抽取长期记忆（③ 期）：<b>旁路调用，绝不拖累新建会话</b>。
     * <p>
     * {@code UserChatMemoryService#extractAsync} 自己已经吞掉了抽取过程中的异常，这里再挡一层的理由
     * 与 {@code AdminChatMemoryService#audit} 相同：这条约定不能只靠"下游实现记得吞"来保证。
     * 换实现、加装饰器、线程池拒绝任务，任何一种都会把异常顺着调用点冒上来，
     * 而本方法在 {@code @Transactional} 里——一次"记忆抽取失败"就能把用户刚点出来的新会话一起回滚掉，
     * 表现成"点了新对话没反应"。记忆是增强项，新建会话是功能本身，两者不能互相牵连。
     */
    private void extractMemoryQuietly(Long userId, Long endedSessionId) {
        try {
            userChatMemoryService.extractAsync(userId, endedSessionId, "session-ended");
        } catch (RuntimeException e) {
            log.warn("会话结束时的记忆抽取提交失败（忽略，不影响新建会话）userId={} sessionId={}",
                    userId, endedSessionId, e);
        }
    }

    /** 插入一条进行中会话；撞 {@code uk_user_active} 时回查并发方建好的那条（与 getOrCreate 同一套收敛语义）。 */
    private ChatSession insertNewSession(Long userId, Integer sessionType) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(DEFAULT_TITLE);
        session.setSessionStatus(CodeEnum.ChatSessionStatus.ACTIVE.getCode());
        session.setSessionType(sessionType == null ? CodeEnum.ChatSessionType.MIXED.getCode() : sessionType);
        session.setModelCode(chatClientRegistry.modelCode(SceneCode.CHAT));
        // 落提示词版本标签（如 chat@v1），而不是文件名：换提示词内容但版本号不变时，历史消息仍可追溯
        session.setSystemPromptVer(promptTemplateService.versionTag(SceneCode.CHAT));
        // V21 起这一列已废弃（读取侧只认 ai.memory.window-messages），仍然写上：
        // 列是 NOT NULL 且带 CHECK(1..200)，写它比留默认值更能反映"创建当时的窗口配置"（追溯用）
        session.setWindowMsgCount(aiProperties.getMemory().getWindowMessages());
        session.setMsgCount(0);
        session.setIsPinned(CodeEnum.YesNo.NO.getCode());
        try {
            chatSessionMapper.insert(session);
            return session;
        } catch (DuplicateKeyException e) {
            if (!SqlDuplicateKeys.isDuplicateKeyFor(e, ChatSessionMapper.UK_USER_ACTIVE)) {
                throw e;
            }
            ChatSession winner = chatSessionMapper.selectActiveByUserId(userId);
            if (winner == null) {
                // 唯一约束说"已有一条进行中会话"，回查却查不到：只可能是它已被并发归档/删除，
                // 或索引与查询条件不一致。宁可报错也不返回一个刚建失败的会话对象。
                throw e;
            }
            log.info("并发建会话落败，复用已有进行中会话 userId={} sessionId={}", userId, winner.getId());
            return winner;
        }
    }

    // ------------------------------------------------------------------ 5.3 列表（含惰性归档）

    /**
     * 会话列表（接口 5.3）。
     * <p>
     * 排序 {@code is_pinned DESC, last_msg_at DESC, id DESC}：置顶在最前，
     * 其次按最近消息时间，最后用 id 兜底保证分页稳定（否则同一时间的多条会话顺序会飘，
     * 翻页时可能重复或漏掉）。{@code last_msg_at} 为 NULL 的空会话在 DESC 里自然排到最后。
     * <p>
     * 「最后一条消息预览」用一次窗口函数查询批量取回，不做 N+1。
     */
    public ChatDtos.SessionListResponse list(Long userId, Integer sessionStatus, Integer pageNum, Integer pageSize) {
        archiveIdleSessions(userId);

        int size = pageSize == null || pageSize <= 0 ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize, aiProperties.getChat().getMaxPageSize());
        int page = pageNum == null || pageNum <= 0 ? 1 : pageNum;

        LambdaQueryWrapper<ChatSession> wrapper = new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .eq(sessionStatus != null, ChatSession::getSessionStatus, sessionStatus)
                .orderByDesc(ChatSession::getIsPinned)
                .orderByDesc(ChatSession::getLastMsgAt)
                .orderByDesc(ChatSession::getId);
        Page<ChatSession> result = chatSessionMapper.selectPage(new Page<>(page, size), wrapper);

        List<ChatSession> rows = result.getRecords();
        Map<Long, String> previews = previews(rows);
        List<ChatDtos.SessionRow> records = new ArrayList<>(rows.size());
        for (ChatSession row : rows) {
            records.add(ChatDtos.SessionRow.builder()
                    .id(row.getId())
                    .title(row.getTitle())
                    .sessionStatus(row.getSessionStatus())
                    .sessionType(row.getSessionType())
                    .msgCount(row.getMsgCount())
                    .isPinned(row.getIsPinned() != null && row.getIsPinned() == CodeEnum.YesNo.YES.getCode())
                    .lastMsgAt(row.getLastMsgAt())
                    .createdAt(row.getGmtCreate())
                    .lastMessagePreview(previews.get(row.getId()))
                    .build());
        }
        return ChatDtos.SessionListResponse.builder()
                .records(records)
                .total(result.getTotal())
                .archiveIdleDays(aiProperties.getChat().getArchiveIdleDays())
                .build();
    }

    /**
     * 惰性归档：把该用户"进行中但太久没消息"的会话置为归档。
     * <p>
     * <b>失败只记日志</b>：归档是展示语义，不能因为一条清理语句失败就让用户看不到会话列表。
     */
    private void archiveIdleSessions(Long userId) {
        int days = aiProperties.getChat().getArchiveIdleDays();
        if (days <= 0) {
            return;
        }
        try {
            int archived = chatSessionMapper.archiveIdleSessions(userId, LocalDateTime.now().minusDays(days));
            if (archived > 0) {
                log.info("惰性归档会话 userId={} 条数={} 阈值={}天", userId, archived, days);
            }
        } catch (RuntimeException e) {
            log.warn("惰性归档会话失败（不影响列表返回）userId={}", userId, e);
        }
    }

    private Map<Long, String> previews(List<ChatSession> rows) {
        List<Long> ids = rows.stream().map(ChatSession::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> previews = new HashMap<>();
        for (Map<String, Object> row : chatMessageMapper.selectLastMessagePerSession(ids)) {
            Object sessionId = row.get("sessionId");
            Object preview = row.get("preview");
            if (sessionId != null && preview != null) {
                previews.put(((Number) sessionId).longValue(), String.valueOf(preview).trim());
            }
        }
        return previews;
    }

    // ------------------------------------------------------------------ 5.4 删除 / 置顶

    /**
     * 删除会话（接口 5.4）：会话与其消息<b>同事务</b>逻辑删除。
     * <p>
     * 幂等语义（三态区分，靠 {@link ChatSessionMapper#selectByIdIncludingDeleted}）：
     * <ul>
     *   <li>从不存在 → 404；</li>
     *   <li>存在但不是自己的 → 404（与其它接口一致，不用 403 泄露"该会话存在"）；</li>
     *   <li>是自己的且已经删过 → 直接成功（重试/双击不该弹错）。</li>
     * </ul>
     * 删掉的如果是进行中会话：`/current` 下次会新建一条（不需要在这里补建）。
     */
    @Transactional
    public void delete(Long userId, Long sessionId) {
        ChatSession session = chatSessionMapper.selectByIdIncludingDeleted(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在或不属于当前用户");
        }
        if (session.getIsDeleted() != null && session.getIsDeleted() == CodeEnum.YesNo.YES.getCode()) {
            log.debug("会话已是删除状态，按幂等成功返回 userId={} sessionId={}", userId, sessionId);
            return;
        }
        int messages = chatMessageMapper.logicDeleteBySession(sessionId, userId);
        if (chatSessionMapper.logicDeleteSession(sessionId, userId) == 0) {
            // 会话在本次事务里被别人删掉了（或状态变了）：消息已删，这里当作幂等成功
            log.info("删除会话时发现它已被并发删除 userId={} sessionId={} 消息已删 {} 条", userId, sessionId, messages);
            return;
        }
        log.info("删除会话 userId={} sessionId={} 一并逻辑删除消息 {} 条", userId, sessionId, messages);
    }

    /** 置顶/取消置顶（契约只给了 is_pinned 字段，没有接口，这里补最小写接口）。 */
    public void setPinned(Long userId, Long sessionId, boolean pinned) {
        requireOwned(userId, sessionId);
        int updated = chatSessionMapper.updatePinned(sessionId, userId,
                pinned ? CodeEnum.YesNo.YES.getCode() : CodeEnum.YesNo.NO.getCode());
        if (updated == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在或不属于当前用户");
        }
        log.info("会话置顶状态更新 userId={} sessionId={} isPinned={}", userId, sessionId, pinned);
    }

    /**
     * 校验会话存在且属于当前用户。
     * 不属于当前用户时按 404 处理（不返回 403，避免泄露「该会话存在」）。
     */
    public ChatSession requireOwned(Long userId, Long sessionId) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在或不属于当前用户");
        }
        return session;
    }

    /** 首条消息到达时用消息内容生成标题（已是自定义标题则不动）。 */
    public void renameIfDefault(Long sessionId, String contentText) {
        if (contentText == null || contentText.isBlank()) {
            return;
        }
        String title = contentText.strip().replaceAll("\\s+", " ");
        if (title.length() > TITLE_MAX) {
            title = title.substring(0, TITLE_MAX);
        }
        chatSessionMapper.renameIfDefault(sessionId, title, DEFAULT_TITLE);
    }

    /**
     * 校验"这个会话还能继续写"（2.24 / 2.25 用）。
     * <p>
     * <b>为什么要挡</b>：归档/已结束的会话代表"这段对话已经翻篇"。若允许继续往里写，
     * 就会出现两种分裂：① 用户在小程序里打开历史会话发消息，消息进了老会话，
     * 而 `/current` 返回的却是另一条新会话（下一次刷新历史就找不到了）；
     * ② 被归档的会话因为一条新消息又"活"过来，但 {@code session_status} 仍是归档，
     * 列表里它既不在"最近"也不在"进行中"，彻底说不清。
     * 因此这里明确拒绝，并让前端提示"该会话已结束，请新建对话"。
     */
    public ChatSession requireActive(Long userId, Long sessionId) {
        ChatSession session = requireOwned(userId, sessionId);
        if (session.getSessionStatus() == null || session.getSessionStatus() != STATUS_ACTIVE) {
            throw new BizException(ErrorCode.BIZ_REJECTED,
                    session.getSessionStatus() != null && session.getSessionStatus() == STATUS_ARCHIVED
                            ? "该会话已归档，不能继续发送消息：请新建对话"
                            : "该会话已结束，不能继续发送消息：请新建对话");
        }
        return session;
    }

    public ChatDtos.SessionResponse toResponse(ChatSession session) {
        return ChatDtos.SessionResponse.from(session);
    }
}
