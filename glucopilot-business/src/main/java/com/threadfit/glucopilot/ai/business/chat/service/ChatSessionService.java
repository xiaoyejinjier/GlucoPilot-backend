package com.threadfit.glucopilot.ai.business.chat.service;

import com.threadfit.glucopilot.ai.business.chat.dto.ChatDtos;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.core.AiChatClientRegistry;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.prompt.AiPromptTemplateService;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 会话生命周期（对应接口 2.23）。L1 只做 get-or-create 与冗余列维护，不做列表/删除（二期）。
 */
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

    /** 2.23 取进行中会话；没有就建一个。 */
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
        chatSessionMapper.insert(session);
        return session;
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
