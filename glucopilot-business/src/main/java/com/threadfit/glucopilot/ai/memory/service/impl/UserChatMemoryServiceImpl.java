package com.threadfit.glucopilot.ai.memory.service.impl;

import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.core.SceneModelResolver;
import com.threadfit.glucopilot.ai.prompt.service.AiPromptTemplateService;
import com.threadfit.glucopilot.ai.support.ChatRoundGuard;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import com.threadfit.glucopilot.ai.memory.controller.UserChatMemoryController;
import com.threadfit.glucopilot.ai.memory.dto.UserChatMemoryDtos;
import com.threadfit.glucopilot.ai.memory.entity.ChatUserMemoryEntity;
import com.threadfit.glucopilot.ai.memory.mapper.ChatUserMemoryMapper;
import com.threadfit.glucopilot.ai.memory.service.UserChatMemoryService;

/**
 * 跨会话长期记忆（③ 期）：抽取、写入、读取与用户自助删除。
 *
 * <h2>为什么它比摘要更需要保守</h2>
 * 摘要是"这段对话聊了什么"，记忆是"你是谁"。一条写错的长期记忆（例如把"用户问过二甲双胍怎么吃"
 * 记成"用户在服用二甲双胍"）会在**后续每一轮**都影响回答，而且用户根本看不到自己被记错了。
 * 因此这里设了四道闸：
 * <ol>
 *   <li><b>键固定</b>：只接受 {@link UserChatMemoryService#ALLOWED_KEYS} 五类，模型自创的键一律丢弃；</li>
 *   <li><b>置信度门限</b>：低于 {@link #MIN_CONFIDENCE} 的一律不写（宁可不记，也不要记半对）；</li>
 *   <li><b>内容体检</b>：命中"剂量/诊断/用药"等危险词的记忆直接丢弃——这类内容只该留在对话里并引导就医；</li>
 *   <li><b>来源可追溯 + 可删</b>：每条都带 {@code source_session_id}，且用户/运营都能一键删掉（合规）。</li>
 * </ol>
 * 同 key 新值覆盖旧值（表上 {@code uk_user_key_active(user_id, memory_key, is_deleted)} 保证同 key 只有一条有效记忆）。
 *
 * <h2>触发时机（刻意不做"每轮都抽"）</h2>
 * <ul>
 *   <li><b>会话结束时</b>（用户点"新对话"、会话被删除）——长期记忆本来就不该每轮都写；</li>
 *   <li><b>用户显式要求记住时</b>（消息里出现"记住/记一下"这类措辞）——这是用户主动表达，最可信。</li>
 * </ul>
 * 每轮都抽的成本是对话的两倍（每轮多一次模型调用），噪声也大得多（刚聊两句就"抽出偏好"）。
 *
 * <h2>失败降级</h2>
 * 抽取失败只记日志：记忆是增强项，绝不能因为一次抽取失败影响已经成功的回复。
 */
@Slf4j
@Service
public class UserChatMemoryServiceImpl implements UserChatMemoryService {

    /** 写入门限：低于它不写（宁可不记，也不要记半对）。 */
    static final int MIN_CONFIDENCE = 60;

    /** 单条记忆文本上限（列是 varchar(512)，但记忆本身就该短，太长说明抽错了）。 */
    static final int MAX_TEXT_CHARS = 200;

    /** 命中这些词的内容不许进长期记忆（诊断/用药类内容只该留在对话里）。 */
    private static final List<String> FORBIDDEN_MARKERS =
            List.of("剂量", "加量", "减量", "停药", "换药", "开药", "处方", "确诊", "诊断为", "胰岛素用量");

    private static final String EXTRACT_KEY_PREFIX = "chat-memory-extract:";

    private final ChatClient memoryClient;
    private final ChatUserMemoryMapper memoryMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final AiPromptTemplateService promptTemplateService;
    private final SceneModelResolver sceneModelResolver;
    private final AIProperties aiProperties;
    private final com.threadfit.glucopilot.common.util.JsonUtils jsonUtils;
    private final ChatRoundGuard extractGuard;
    private final Executor executor;

    public UserChatMemoryServiceImpl(@Qualifier("summaryChatClient") @Nullable ChatClient memoryClient,
                                 ChatUserMemoryMapper memoryMapper,
                                 ChatMessageMapper chatMessageMapper,
                                 AiPromptTemplateService promptTemplateService,
                                 SceneModelResolver sceneModelResolver,
                                 AIProperties aiProperties,
                                 com.threadfit.glucopilot.common.util.JsonUtils jsonUtils,
                                 ChatRoundGuard roundGuard,
                                 @Qualifier("chatSummaryExecutor") Executor executor) {
        this.memoryClient = memoryClient;
        this.memoryMapper = memoryMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.promptTemplateService = promptTemplateService;
        this.sceneModelResolver = sceneModelResolver;
        this.aiProperties = aiProperties;
        this.jsonUtils = jsonUtils;
        // 与摘要共用同一个单飞守卫（键不同）与同一个线程池：
        // 两者都是"回复之后的后台增强"，没必要各起一个池；线程池饿了宁可丢记忆也不拖慢对话
        this.extractGuard = roundGuard;
        this.executor = executor;
    }

    // ------------------------------------------------------------------ 读取（供上下文装配）

    /**
     * 取要注入上下文的记忆：按置信度降序、更新时间降序，最多 {@code ai.memory.inject-max-items} 条。
     * <p>
     * 读失败返回空列表（记忆是增强项，读不到就当没有，绝不让这一轮问答失败）。
     */
    @Override
    public List<MemoryItem> forInjection(Long userId) {
        if (userId == null) {
            return List.of();
        }
        int max = aiProperties.getMemory().getInjectMaxItems();
        if (max <= 0) {
            return List.of();
        }
        try {
            List<ChatUserMemoryEntity> rows = memoryMapper.selectList(
                    new LambdaQueryWrapper<ChatUserMemoryEntity>()
                            .eq(ChatUserMemoryEntity::getUserId, userId)
                            .orderByDesc(ChatUserMemoryEntity::getConfidence)
                            .orderByDesc(ChatUserMemoryEntity::getGmtModified)
                            .last("LIMIT " + max));
            List<MemoryItem> items = new ArrayList<>();
            for (ChatUserMemoryEntity row : rows) {
                items.add(new MemoryItem(row.getMemoryKey(), row.getMemoryText(),
                        row.getConfidence() == null ? 100 : row.getConfidence()));
            }
            return items;
        } catch (RuntimeException e) {
            log.warn("读取长期记忆失败（本轮不注入）userId={}", userId, e);
            return List.of();
        }
    }

    /**
     * 用户自己看自己记忆（C 端合规自助，见 {@code UserChatMemoryController}）。
     *
     * <h2>为什么这里与 {@link #forInjection} 分开，而不是复用它</h2>
     * 两者的失败语义相反：
     * <ul>
     *   <li>{@link #forInjection} 是<b>增强项</b>——读不到就当没有，绝不能让这一轮问答失败；</li>
     *   <li>这里回答的是"系统到底记住了我什么"，读失败却返回空列表，等于告诉用户"没记你任何东西"，
     *       而事实相反。合规场景里这种"善意的降级"就是撒谎，因此读失败<b>照常抛出</b>，
     *       让用户看到错误并重试。</li>
     * </ul>
     * 另外它不做 {@code inject-max-items} 截断：那个上限是为了省 token，不是为了隐瞒；
     * 这里给出全部有效记忆（键只允许五类、同键至多一条，行数天然有界）。
     */
    @Override
    public List<UserChatMemoryDtos.MemoryRow> listOwnForUser(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<ChatUserMemoryEntity> rows = memoryMapper.selectList(
                new LambdaQueryWrapper<ChatUserMemoryEntity>()
                        .eq(ChatUserMemoryEntity::getUserId, userId)
                        .orderByDesc(ChatUserMemoryEntity::getConfidence)
                        .orderByDesc(ChatUserMemoryEntity::getGmtModified)
                        .orderByDesc(ChatUserMemoryEntity::getId));
        List<UserChatMemoryDtos.MemoryRow> items = new ArrayList<>(rows.size());
        for (ChatUserMemoryEntity row : rows) {
            items.add(UserChatMemoryDtos.MemoryRow.builder()
                    .memoryKey(row.getMemoryKey())
                    .memoryText(row.getMemoryText())
                    .confidence(row.getConfidence())
                    .updatedAt(row.getGmtModified())
                    .build());
        }
        return items;
    }

    /** 用户自助删除全部长期记忆（合规：用户有权清掉系统对他的记忆）。返回删除条数。 */
    @Override
    public int deleteAllForUser(Long userId) {
        if (userId == null) {
            return 0;
        }
        // ⚠️ 不能用 MP 内置逻辑删除：它只置 is_deleted=1，而 DDL 的 chk_cum_del_consistent 要求
        // 删除态必须同时写 deleted_at，MySQL 8 会直接拒绝（H2 实测复现过）。因此走显式 UPDATE。
        // deleted_by 传 null：这是用户自己行使删除权，不是运营代删，把 C 端 user_id 记进
        // "操作人"列会让管理端的删除留痕把用户和运营混在一起（运营侧的留痕走 ops_audit_log）。
        int deleted = memoryMapper.logicDeleteAllByUser(userId, null);
        log.info("用户自助清除长期记忆 userId={} 条数={}", userId, deleted);
        return deleted;
    }

    // ------------------------------------------------------------------ 写入

    /**
     * 写入/覆盖一条记忆（同 key 覆盖）。
     * <p>
     * 返回是否写入。四道闸（键、置信度、长度、危险词）在这里统一施加——
     * 调用方（抽取路径）不需要自己重复判断，也就不可能漏掉某一道。
     */
    boolean upsert(Long userId, String key, String text, Integer confidence, Long sourceSessionId) {
        String normalizedKey = StringUtils.hasText(key) ? key.trim() : "";
        if (!ALLOWED_KEYS.contains(normalizedKey)) {
            log.debug("丢弃不在允许清单里的记忆键：key={} userId={}", key, userId);
            return false;
        }
        String normalizedText = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (!StringUtils.hasText(normalizedText) || normalizedText.length() > MAX_TEXT_CHARS) {
            log.debug("丢弃长度不合适的记忆：key={} len={}", normalizedKey, normalizedText.length());
            return false;
        }
        int score = confidence == null ? 0 : confidence;
        if (score < MIN_CONFIDENCE) {
            log.debug("丢弃置信度不足的记忆：key={} confidence={}", normalizedKey, score);
            return false;
        }
        for (String marker : FORBIDDEN_MARKERS) {
            if (normalizedText.contains(marker)) {
                // 诊断/用药类内容不许进长期记忆：它会在此后每一轮里被当作"用户的事实"复述
                log.info("丢弃含诊断/用药内容的记忆：key={} userId={}（内容不入库，仅记键名）", normalizedKey, userId);
                return false;
            }
        }

        ChatUserMemoryEntity existing = memoryMapper.selectOne(new LambdaQueryWrapper<ChatUserMemoryEntity>()
                .eq(ChatUserMemoryEntity::getUserId, userId)
                .eq(ChatUserMemoryEntity::getMemoryKey, normalizedKey)
                .last("LIMIT 1"));
        if (existing == null) {
            ChatUserMemoryEntity row = new ChatUserMemoryEntity();
            row.setUserId(userId);
            row.setMemoryKey(normalizedKey);
            row.setMemoryText(normalizedText);
            row.setConfidence(Math.min(score, 100));
            row.setSourceSessionId(sourceSessionId);
            memoryMapper.insert(row);
            log.info("新增长期记忆 userId={} key={} confidence={} 来源会话={}",
                    userId, normalizedKey, score, sourceSessionId);
            return true;
        }
        ChatUserMemoryEntity update = new ChatUserMemoryEntity();
        update.setId(existing.getId());
        update.setMemoryText(normalizedText);
        update.setConfidence(Math.min(score, 100));
        update.setSourceSessionId(sourceSessionId);
        memoryMapper.updateById(update);
        log.info("覆盖长期记忆 userId={} key={} confidence={}（旧值长度 {} → 新值长度 {}）",
                userId, normalizedKey, score,
                existing.getMemoryText() == null ? 0 : existing.getMemoryText().length(), normalizedText.length());
        return true;
    }

    /** 账号注销：物理删除该用户全部长期记忆。 */
    @Override
    public void purgeAccountData(Long userId) {
        memoryMapper.purgeByUserId(userId);
    }

    // ------------------------------------------------------------------ 抽取（异步）

    /** 会话结束时调用：异步抽取该会话的长期记忆。不阻塞、不抛异常。 */
    @Override
    public void extractAsync(Long userId, Long sessionId, String reason) {
        if (memoryClient == null || userId == null || sessionId == null) {
            return;
        }
        String key = EXTRACT_KEY_PREFIX + sessionId;
        if (!extractGuard.tryAcquire(key)) {
            log.debug("该会话已有记忆抽取任务在跑，跳过 sessionId={}", sessionId);
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    runExtract(userId, sessionId, reason);
                } finally {
                    extractGuard.release(key);
                }
            });
        } catch (RuntimeException e) {
            extractGuard.release(key);
            log.warn("记忆抽取任务提交失败（忽略）sessionId={}", sessionId, e);
        }
    }


    /**
     * 真正执行一次抽取（包级可见，供单测同步调用）。
     *
     * @return 写入/覆盖的记忆条数
     */
    int runExtract(Long userId, Long sessionId, String reason) {
        if (memoryClient == null) {
            return 0;
        }
        try {
            List<ChatMessage> messages = chatMessageMapper.selectRecentBySession(sessionId,
                    aiProperties.getMemory().getMemoryExtractMaxMessages());
            if (messages == null || messages.isEmpty()) {
                return 0;
            }
            String input = buildInput(userId, messages);
            String raw = memoryClient.prompt()
                    .system(promptTemplateService.systemPrompt(SceneCode.MEMORY))
                    .options(sceneModelResolver.memoryOptionsBuilder())
                    .user(input)
                    .call()
                    .content();
            List<MemoryCandidate> candidates = parse(raw);
            int written = 0;
            for (MemoryCandidate candidate : candidates) {
                if (upsert(userId, candidate.key(), candidate.text(), candidate.confidence(), sessionId)) {
                    written++;
                }
            }
            log.info("长期记忆抽取完成 userId={} sessionId={} 原因={} 候选={} 写入={}",
                    userId, sessionId, reason, candidates.size(), written);
            return written;
        } catch (RuntimeException e) {
            // 记忆是增强项：抽取失败绝不能影响已经成功的回复
            log.warn("长期记忆抽取失败（忽略，下次再试）userId={} sessionId={}", userId, sessionId, e);
            return 0;
        }
    }

    /** 抽取输入：已知记忆（供模型判断"要不要更正"）+ 对话原文（时间正序，超长只取最近部分）。 */
    private String buildInput(Long userId, List<ChatMessage> recentDesc) {
        StringBuilder sb = new StringBuilder();
        List<MemoryItem> known = forInjection(userId);
        if (!known.isEmpty()) {
            sb.append("【已知的长期记忆（如与原文冲突，请输出更正后的说法）】\n");
            for (MemoryItem item : known) {
                sb.append("- ").append(item.key()).append('：').append(item.text()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("【需要分析的对话原文（时间正序）】\n");
        int budget = Math.max(aiProperties.getMemory().getMemoryExtractInputMaxChars(), 500);
        List<String> lines = new ArrayList<>();
        for (ChatMessage row : recentDesc) {
            String text = replayText(row);
            if (!StringUtils.hasText(text)) {
                continue;
            }
            String role = row.getMsgRole() != null && row.getMsgRole() == CodeEnum.ChatMsgRole.USER.getCode()
                    ? "用户" : "助手";
            lines.add(role + "：" + text.replaceAll("\\s+", " ").trim());
        }
        int total = 0;
        List<String> kept = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (total + line.length() > budget && !kept.isEmpty()) {
                break;
            }
            kept.add(line);
            total += line.length();
        }
        java.util.Collections.reverse(kept);
        for (String line : kept) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String replayText(ChatMessage row) {
        if (StringUtils.hasText(row.getContentText())) {
            return row.getContentText();
        }
        if (StringUtils.hasText(row.getMediaCaption())) {
            return "[用户发来的图片，内容摘要] " + row.getMediaCaption();
        }
        return null;
    }

    /** 一条候选记忆。 */
    private record MemoryCandidate(String key, String text, Integer confidence) {
    }

    /**
     * 解析模型输出的 JSON。任何解析失败都返回空列表（宁可这次不记，也不要写进半截内容）。
     * <p>
     * 用宽松解析（遍历 {@code memories} 数组的每个对象），并对每个字段做类型兜底：
     * 模型偶尔会把 confidence 写成字符串、或把 text 写成数字。
     */
    @SuppressWarnings("unchecked")
    private List<MemoryCandidate> parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        String json = raw.trim().replaceFirst("(?s)^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        try {
            Map<String, Object> root = jsonUtils.fromJson(json, Map.class);
            Object memories = root == null ? null : root.get("memories");
            if (!(memories instanceof List<?> list)) {
                return List.of();
            }
            Map<String, MemoryCandidate> byKey = new LinkedHashMap<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                Object key = map.get("key");
                Object text = map.get("text");
                Object confidence = map.get("confidence");
                if (key == null || text == null) {
                    continue;
                }
                int score = confidence instanceof Number number ? number.intValue() : 0;
                MemoryCandidate candidate = new MemoryCandidate(String.valueOf(key), String.valueOf(text), score);
                // 同 key 取置信度更高的那条（提示词已要求只输出一条，这里是防御）
                MemoryCandidate existing = byKey.get(candidate.key());
                if (existing == null || candidate.confidence() > existing.confidence()) {
                    byKey.put(candidate.key(), candidate);
                }
            }
            return new ArrayList<>(byKey.values());
        } catch (RuntimeException e) {
            log.warn("长期记忆抽取结果解析失败（本次不写任何记忆）长度={}", json.length(), e);
            return List.of();
        }
    }

    /** 当前是否有抽取任务在跑（E2E/断言用）。 */
    @Override
    public boolean inFlight() {
        return extractGuard.inFlightCount() > 0;
    }

    /** 供排障：记忆表最近更新时间（E2E 用）。 */
    LocalDateTime lastUpdatedAt(Long userId) {
        ChatUserMemoryEntity row = memoryMapper.selectOne(new LambdaQueryWrapper<ChatUserMemoryEntity>()
                .eq(ChatUserMemoryEntity::getUserId, userId)
                .orderByDesc(ChatUserMemoryEntity::getGmtModified)
                .last("LIMIT 1"));
        return row == null ? null : row.getGmtModified();
    }
}