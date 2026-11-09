package com.threadfit.glucopilot.ai.business.chat.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.ai.business.chat.dto.AdminChatSessionDtos;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import com.threadfit.glucopilot.ai.business.chat.mapper.AdminSessionHit;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatContextMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.SessionRoundCount;
import com.threadfit.glucopilot.ai.business.chat.service.AdminChatSessionService;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.audit.OpsAuditRecorder;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 管理端会话审计的读取实现。
 *
 * <h2>三个刻意的选择</h2>
 * <ol>
 *   <li><b>列表不读正文</b>：{@code context_summary} 是 MEDIUMTEXT，只在 SQL 里折成 0/1；
 *       消息正文同理只取前 200 字。这样一页 20 行的响应大小与"看 20 段问诊"解耦。</li>
 *   <li><b>分片轮次批量取</b>：一页 20 个会话一次 {@code IN} 查询，不做 N+1。</li>
 *   <li><b>只有读全文写审计</b>：列表与预览是"定位"，读全文是"取证"。审计只记结构信息
 *       （{@code {"sessionId":139}}），绝不把正文写进 {@code detail_json}——
 *       审计表一旦成为第二份内容副本，删除权与最小化原则就同时失效了。</li>
 * </ol>
 *
 * <h2>为什么关键词限制长度</h2>
 * {@code LOCATE(#{keyword}, …)} 是前缀无关的全表匹配，超长关键词既无意义又让索引彻底失效；
 * 这里与社区列表保持同一口径（≤50 字），超长直接 400，而不是悄悄截断——
 * 截断会让运营以为"搜了全部"而实际只搜了前 50 字。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminChatSessionServiceImpl implements AdminChatSessionService {

    /** 关键词上限：与前端输入框的限制保持一致。 */
    static final int KEYWORD_MAX_CHARS = 50;

    /** 预览上限：够认出"哪一轮"，又不至于把整段问诊带进列表接口。 */
    static final int PREVIEW_MAX_CHARS = 200;

    /** 折叠空白：日志/多行粘贴进来的正文会把表格撑坏，预览里统一压成单空格。 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final String AUDIT_MODULE = "ai";
    private static final String AUDIT_ACTION_VIEW_MESSAGE = "ai_chat_audit_view";
    private static final String AUDIT_ACTION_NAME = "查看会话消息原文";
    private static final String AUDIT_TARGET_TYPE = "chat_message";

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ChatContextMapper contextMapper;
    private final OpsAuditRecorder auditRecorder;

    @Override
    public PageResult<AdminChatSessionDtos.SessionRow> list(Long userId, Integer status, String keyword, PageQuery query) {
        String normalizedKeyword = normalizeKeyword(keyword);
        Page<AdminSessionHit> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<AdminSessionHit> result = sessionMapper.selectAdminPage(page, userId, status, normalizedKeyword);

        Map<Long, Integer> roundsBySession = roundCounts(result.getRecords());
        List<AdminChatSessionDtos.SessionRow> rows = result.getRecords().stream()
                .map(hit -> new AdminChatSessionDtos.SessionRow(
                        hit.getId() == null ? 0L : hit.getId(),
                        hit.getUserId() == null ? 0L : hit.getUserId(),
                        hit.getUserNickname(),
                        hit.getTitle(),
                        hit.getSessionStatus() == null ? 0 : hit.getSessionStatus(),
                        hit.getSessionType() == null ? 0 : hit.getSessionType(),
                        hit.getModelCode(),
                        hit.getSystemPromptVer(),
                        hit.getMsgCount() == null ? 0 : hit.getMsgCount(),
                        roundsBySession.getOrDefault(hit.getId(), 0),
                        hit.getHasSummary() != null && hit.getHasSummary() == 1,
                        hit.getLastMsgAt(),
                        hit.getGmtCreate()))
                .toList();
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), rows);
    }

    @Override
    public PageResult<AdminChatSessionDtos.MessageRow> messages(long sessionId, Integer beforeSeq, PageQuery query) {
        requireSession(sessionId);
        Page<ChatMessage> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<ChatMessage> result = messageMapper.selectAdminPage(page, sessionId, beforeSeq);
        List<AdminChatSessionDtos.MessageRow> rows = result.getRecords().stream()
                .map(message -> new AdminChatSessionDtos.MessageRow(
                        message.getId(),
                        message.getSeqNo() == null ? 0 : message.getSeqNo(),
                        message.getMsgRole() == null ? 0 : message.getMsgRole(),
                        message.getMsgType() == null ? 0 : message.getMsgType(),
                        preview(message),
                        message.getModelCode(),
                        message.getPromptTokens(),
                        message.getCompletionTokens(),
                        message.getLatencyMs(),
                        message.getFinishReason(),
                        message.getGmtCreate()))
                .toList();
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), rows);
    }

    @Override
    public AdminChatSessionDtos.MessageDetail messageDetail(long sessionId, long messageId) {
        requireSession(sessionId);
        ChatMessage message = messageMapper.selectById(messageId);
        // 消息必须真属于这个会话：否则运营可以拿 A 会话的 id 去读 B 会话的消息，
        // 而审计里记下的 sessionId 会是错的（等于留了一条假案发现场）。
        if (message == null || !sessionIdEquals(message.getSessionId(), sessionId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "消息不存在或不属于该会话：" + messageId);
        }
        auditRecorder.record(AUDIT_MODULE, AUDIT_ACTION_VIEW_MESSAGE, AUDIT_ACTION_NAME,
                AUDIT_TARGET_TYPE, messageId, "{\"sessionId\":" + sessionId + "}");
        log.info("管理端读取会话消息原文：sessionId={} messageId={} seqNo={}",
                sessionId, messageId, message.getSeqNo());
        return new AdminChatSessionDtos.MessageDetail(
                message.getId(),
                sessionId,
                message.getUserId() == null ? 0L : message.getUserId(),
                message.getSeqNo() == null ? 0 : message.getSeqNo(),
                message.getMsgRole() == null ? 0 : message.getMsgRole(),
                message.getMsgType() == null ? 0 : message.getMsgType(),
                message.getContentText(),
                message.getMediaCaption(),
                message.getAsrText(),
                message.getMediaId(),
                message.getVoiceMediaId(),
                message.getVoiceDurationMs(),
                message.getReplyToMsgId(),
                message.getModelCode(),
                message.getPromptTokens(),
                message.getCompletionTokens(),
                message.getLatencyMs(),
                message.getFinishReason(),
                message.getGmtCreate());
    }

    /** 会话不存在（或已逻辑删除）直接 404：审计页不该对不存在的 id 返回空列表。 */
    private void requireSession(long sessionId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在或已删除：" + sessionId);
        }
    }

    /** 一页会话的分片轮次：一次 IN 查询，缺省 0。 */
    private Map<Long, Integer> roundCounts(List<AdminSessionHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = hits.stream().map(AdminSessionHit::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (SessionRoundCount row : contextMapper.selectRoundCounts(ids)) {
            counts.put(row.getSessionId(), row.getRounds() == null ? 0 : row.getRounds());
        }
        return counts;
    }

    private static boolean sessionIdEquals(Long actual, long expected) {
        return actual != null && actual == expected;
    }

    /** 空 → null；超长 → 400（不静默截断，见类注释）。 */
    private static String normalizeKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.length() > KEYWORD_MAX_CHARS) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "关键词最长 " + KEYWORD_MAX_CHARS + " 个字符，当前 " + trimmed.length() + " 个");
        }
        return trimmed;
    }

    /**
     * 预览：正文 → 图片描述 → 语音转写，全空时给类型占位。
     * <p>空白折叠后再截断，避免一屏只有换行。
     */
    static String preview(ChatMessage message) {
        String text = firstNonBlank(message.getContentText(), message.getMediaCaption(), message.getAsrText());
        if (!StringUtils.hasText(text)) {
            int type = message.getMsgType() == null ? 1 : message.getMsgType();
            text = switch (type) {
                case 2 -> "[图片]";
                case 3 -> "[语音]";
                default -> "[空消息]";
            };
        }
        String compact = WHITESPACE.matcher(text).replaceAll(" ").trim();
        return compact.length() <= PREVIEW_MAX_CHARS ? compact : compact.substring(0, PREVIEW_MAX_CHARS) + "…";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
