package com.threadfit.glucopilot.ai.business.chat.service;

import com.threadfit.glucopilot.ai.business.chat.dto.ChatDtos;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.core.AiChatClientRegistry;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.prompt.AiPromptTemplateService;
import com.threadfit.glucopilot.ai.support.SqlDuplicateKeys;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 会话生命周期（对应接口 2.23）。L1 只做 get-or-create 与冗余列维护，不做列表/删除（二期）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    /** 会话默认标题 */
    public static final String DEFAULT_TITLE = "新对话";
    /** 标题截取长度 */
    private static final int TITLE_MAX = 20;

    private final ChatSessionMapper chatSessionMapper;
    private final AIProperties aiProperties;
    private final AiChatClientRegistry chatClientRegistry;
    private final AiPromptTemplateService promptTemplateService;

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
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(DEFAULT_TITLE);
        session.setSessionStatus(CodeEnum.ChatSessionStatus.ACTIVE.getCode());
        session.setSessionType(sessionType == null ? CodeEnum.ChatSessionType.MIXED.getCode() : sessionType);
        session.setModelCode(chatClientRegistry.modelCode(SceneCode.CHAT));
        // 落提示词版本标签（如 chat@v1），而不是文件名：换提示词内容但版本号不变时，历史消息仍可追溯
        session.setSystemPromptVer(promptTemplateService.versionTag(SceneCode.CHAT));
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

    public ChatDtos.SessionResponse toResponse(ChatSession session) {
        return ChatDtos.SessionResponse.from(session);
    }
}
