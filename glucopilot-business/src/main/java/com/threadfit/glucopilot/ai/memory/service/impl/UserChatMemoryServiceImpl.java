package com.threadfit.glucopilot.ai.memory.service.impl;

import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.config.properties.AIProperties;
import com.threadfit.glucopilot.ai.client.AiChatClientRegistry;
import com.threadfit.glucopilot.ai.scene.SceneCode;
import com.threadfit.glucopilot.ai.routing.SceneModelResolver;
import com.threadfit.glucopilot.ai.prompt.service.AiPromptTemplateService;
import com.threadfit.glucopilot.ai.support.ChatRoundGuard;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
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
import com.threadfit.glucopilot.ai.memory.dto.MemoryExtractionDtos;
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

    /**
     * 显式写入（{@code save_long_term_memory} 工具路径）的固定置信度。
     * <p>
     * 85 而不是 100：它不是"模型多确定"，只是"用户亲口说的"这一档先验——留出余量，
     * 以后若要做多来源加权（例如用户手改的记忆给 100），这一档才有区分度。
     */
    static final int EXPLICIT_CONFIDENCE = 85;

    /** 命中这些词的内容不许进长期记忆（诊断/用药类内容只该留在对话里）。 */
    private static final List<String> FORBIDDEN_MARKERS =
            List.of("剂量", "加量", "减量", "停药", "换药", "开药", "处方", "确诊", "诊断为", "胰岛素用量");

    private static final String EXTRACT_KEY_PREFIX = "chat-memory-extract:";

    /**
     * 结构化输出校验器（复用同一个实例：它只持有 schema 与重试次数，无状态）。
     * <p>
     * {@code entity(...)} 只是把"期望的格式说明"放进请求 context，真正把说明追加到用户消息、
     * 并在返回后按 JSON Schema 校验 + 重试的是这个 advisor——只调 {@code entity()} 不挂它会拿不到
     * schema 约束（模型可能返回带解释文字的自由文本）。
     */
    private static final StructuredOutputValidationAdvisor STRUCTURED_OUTPUT_ADVISOR =
            StructuredOutputValidationAdvisor.builder()
                    .outputType(MemoryExtractionDtos.MemoryPayload.class)
                    .maxRepeatAttempts(1)
                    .build();

    /** 记忆抽取用的「裸」文本客户端（不带人设/工具），取用点统一收在 registry 里。 */
    private final AiChatClientRegistry clients;
    private final ChatUserMemoryMapper memoryMapper;
    private final ChatMessageMapper chatMessageMapper;
    /** 只用于记录「抽到哪一条」（V61），不参与抽取本身。 */
    private final ChatSessionMapper chatSessionMapper;
    private final AiPromptTemplateService promptTemplateService;
    private final SceneModelResolver sceneModelResolver;
    private final AIProperties aiProperties;
    private final com.threadfit.glucopilot.common.util.JsonUtils jsonUtils;
    private final ChatRoundGuard extractGuard;
    private final Executor executor;

    public UserChatMemoryServiceImpl(AiChatClientRegistry clients,
                                 ChatUserMemoryMapper memoryMapper,
                                 ChatMessageMapper chatMessageMapper,
                                 ChatSessionMapper chatSessionMapper,
                                 AiPromptTemplateService promptTemplateService,
                                 SceneModelResolver sceneModelResolver,
                                 AIProperties aiProperties,
                                 com.threadfit.glucopilot.common.util.JsonUtils jsonUtils,
                                 ChatRoundGuard roundGuard,
                                 @Qualifier("chatSummaryExecutor") Executor executor) {
        this.clients = clients;
        this.memoryMapper = memoryMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.chatSessionMapper = chatSessionMapper;
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
     * 显式写入（工具 {@code save_long_term_memory} 的落地）。
     * <p>
     * 置信度固定 {@link #EXPLICIT_CONFIDENCE}：这句话是用户当轮亲口说的，让模型自评"我有多确定"
     * 只会引入噪声（它可能给出 40 分导致一条真事实被丢掉）。真正的把关是四道闸，不是模型的自评。
     */
    @Override
    public SaveOutcome saveExplicit(Long userId, Long sourceSessionId, String key, String text) {
        if (userId == null) {
            // 正常路径不会走到：工具调用前 AiToolContext.requireUserId 已经拦过
            return SaveOutcome.rejected("缺少用户上下文，未记录");
        }
        return write(userId, key, text, EXPLICIT_CONFIDENCE, sourceSessionId, WriteSource.EXPLICIT);
    }

    /**
     * 写入/覆盖一条记忆（同 key 覆盖）。
     * <p>
     * 四道闸（键、长度、置信度、危险词）在<b>这里统一施加</b>——三条写入入口（工具、会话结束、
     * 归档补齐）都从这里过，调用方不需要自己重复判断，也就不可能漏掉某一道。
     * <p>
     * 冲突策略由 {@code source} 决定：
     * <ul>
     *   <li>{@link WriteSource#EXPLICIT}：无条件覆盖（用户刚说的算数）；</li>
     *   <li>{@link WriteSource#BATCH}：只有新置信度 ≥ 既有置信度时才覆盖——否则一段老会话的
     *       迟到抽取会把用户今天刚说的事实盖回旧版本。</li>
     * </ul>
     * 返回的是给模型/日志看的回执，拒绝原因是可执行的具体说明，不是一个笼统的"失败"。
     */
    SaveOutcome write(Long userId, String key, String text, Integer confidence, Long sourceSessionId,
                      WriteSource source) {
        String normalizedKey = StringUtils.hasText(key) ? key.trim() : "";
        if (!ALLOWED_KEYS.contains(normalizedKey)) {
            log.debug("丢弃不在允许清单里的记忆键：key={} userId={}", key, userId);
            return SaveOutcome.rejected("键不合法，只能是 diet_taboo（忌口）/ glucose_goal（控糖目标）"
                    + "/ health_context（健康状况）/ habit（习惯）/ preference（偏好）");
        }
        String normalizedText = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (!StringUtils.hasText(normalizedText) || normalizedText.length() > MAX_TEXT_CHARS) {
            log.debug("丢弃长度不合适的记忆：key={} len={}", normalizedKey, normalizedText.length());
            return SaveOutcome.rejected("内容为空或超过 " + MAX_TEXT_CHARS + " 字，未记录");
        }
        int score = confidence == null ? 0 : confidence;
        if (score < MIN_CONFIDENCE) {
            log.debug("丢弃置信度不足的记忆：key={} confidence={}", normalizedKey, score);
            return SaveOutcome.rejected("置信度不足（" + score + " < " + MIN_CONFIDENCE + "），未记录");
        }
        for (String marker : FORBIDDEN_MARKERS) {
            if (normalizedText.contains(marker)) {
                // 诊断/用药类内容不许进长期记忆：它会在此后每一轮里被当作"用户的事实"复述
                log.info("丢弃含诊断/用药内容的记忆：key={} userId={}（内容不入库，仅记键名）", normalizedKey, userId);
                return SaveOutcome.rejected("内容涉及诊断或用药，长期记忆不保存这类信息，未记录");
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
            log.info("新增长期记忆 userId={} key={} confidence={} 来源会话={} 来源={}",
                    userId, normalizedKey, score, sourceSessionId, source);
            return SaveOutcome.ok();
        }
        int existingConfidence = existing.getConfidence() == null ? 0 : existing.getConfidence();
        if (source == WriteSource.BATCH && score < existingConfidence) {
            log.info("跳过覆盖长期记忆 userId={} key={}（批量抽取置信度 {} < 既有 {}）",
                    userId, normalizedKey, score, existingConfidence);
            return SaveOutcome.rejected("已有更可信的记忆（置信度 " + existingConfidence + "），本次不覆盖");
        }
        if (normalizedText.equals(existing.getMemoryText())) {
            // 内容没变化就不写库：避免每次会话结束都刷新 gmt_modified，把"最近更新"变成噪声
            log.debug("记忆内容未变化，跳过写入 userId={} key={}", userId, normalizedKey);
            return SaveOutcome.ok();
        }
        ChatUserMemoryEntity update = new ChatUserMemoryEntity();
        update.setId(existing.getId());
        update.setMemoryText(normalizedText);
        update.setConfidence(Math.min(score, 100));
        update.setSourceSessionId(sourceSessionId);
        memoryMapper.updateById(update);
        log.info("覆盖长期记忆 userId={} key={} confidence={} 来源={}（旧值长度 {} → 新值长度 {}）",
                userId, normalizedKey, score, source,
                existing.getMemoryText() == null ? 0 : existing.getMemoryText().length(), normalizedText.length());
        return SaveOutcome.ok();
    }

    /**
     * 批量抽取用的布尔壳：保留旧签名让抽取循环不必关心回执文案。
     * <p>
     * 走 {@link WriteSource#BATCH}——旧会话的迟到抽取不许盖掉更新的事实。
     */
    boolean upsert(Long userId, String key, String text, Integer confidence, Long sourceSessionId) {
        return write(userId, key, text, confidence, sourceSessionId, WriteSource.BATCH).saved();
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
        if (!clients.utilityAvailable() || userId == null || sessionId == null) {
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
        if (!clients.utilityAvailable()) {
            return 0;
        }
        try {
            int windowSize = Math.max(aiProperties.getMemory().getMemoryExtractWindowMessages(), 1);
            int maxWindows = Math.max(aiProperties.getMemory().getMemoryExtractMaxWindows(), 1);
            List<ChatMessage> recent = chatMessageMapper.selectRecentBySession(sessionId, windowSize * maxWindows);
            if (recent == null || recent.isEmpty()) {
                return 0;
            }
            int written = 0;
            int windows = 0;
            // 老窗先、新窗后：两个窗口提到同一个键时，后写的新说法才留得下来
            for (List<ChatMessage> window : splitOldestFirst(recent, windowSize)) {
                windows++;
                List<MemoryCandidate> candidates = extractCandidates(buildInput(userId, window));
                for (MemoryCandidate candidate : candidates) {
                    if (upsert(userId, candidate.key(), candidate.text(), candidate.confidence(), sessionId)) {
                        written++;
                    }
                }
            }
            // recent 是「新→旧」，第 0 条就是这次覆盖到的上界；工具路径不写这一列，见 markExtracted 注释
            markExtracted(sessionId, recent.get(0).getId());
            log.info("长期记忆抽取完成 userId={} sessionId={} 原因={} 窗口={} 覆盖消息={} 写入={}",
                    userId, sessionId, reason, windows, recent.size(), written);
            return written;
        } catch (RuntimeException e) {
            // 记忆是增强项：抽取失败绝不能影响已经成功的回复
            log.warn("长期记忆抽取失败（忽略，下次再试）userId={} sessionId={}", userId, sessionId, e);
            return 0;
        }
    }

    /**
     * 记录「长期记忆抽到哪一条」（V61 列 {@code chat_session.memory_extracted_upto_msg_id}）。
     * <p>
     * 用途只有一个：惰性归档时判断这个会话值不值得再补抽一次。它是<b>进度标记</b>，不是"抽全了"的证明——
     * 窗口只覆盖最近若干条，更早的消息可能从未进过模型。
     * <p>
     * 失败只告警：进度标记写不进去，最坏是下次归档重抽一遍（浪费一次模型调用），
     * 不能因此把已经写入成功的记忆回滚或让调用方以为抽取失败。
     */
    private void markExtracted(Long sessionId, Long uptoMsgId) {
        if (sessionId == null || uptoMsgId == null) {
            return;
        }
        try {
            chatSessionMapper.markMemoryExtracted(sessionId, uptoMsgId);
        } catch (RuntimeException e) {
            log.warn("记录长期记忆抽取位置失败（忽略，下次归档会重抽）sessionId={} upto={}",
                    sessionId, uptoMsgId, e);
        }
    }

    /**
     * 把「最近 N 条，新→旧」切成若干窗口，按<b>旧→新</b>返回。
     *
     * <p>入参是 {@code selectRecentBySession} 的结果（{@code ORDER BY seq_no DESC}），
     * 所以从尾部往前切片，切出来的第一段就是最老的一段；调用方按返回顺序依次送模型，
     * 后处理的窗口天然覆盖先处理的结论。
     *
     * @param recentDesc 新→旧的消息列表
     * @param windowSize 每个窗口的条数（≥1，调用方已兜底）
     */
    static List<List<ChatMessage>> splitOldestFirst(List<ChatMessage> recentDesc, int windowSize) {
        List<List<ChatMessage>> windows = new ArrayList<>();
        for (int end = recentDesc.size(); end > 0; end -= windowSize) {
            int start = Math.max(0, end - windowSize);
            // subList 仍是「新→旧」，翻正后才是模型看到的时间顺序
            List<ChatMessage> slice = new ArrayList<>(recentDesc.subList(start, end));
            // 从尾部往前切：第一刀切出来的就是最老的一段，所以循环顺序天然是「老→新」
            java.util.Collections.reverse(slice);
            windows.add(slice);
        }
        return windows;
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
     * 调一次模型拿候选记忆：默认走结构化输出（JSON Schema 校验），关掉开关时退回自由文本 + 宽松解析。
     *
     * <p>两条路径的失败语义一致：抛 {@link RuntimeException} 由 {@code runExtract} 的 catch 兜住，
     * 本轮一条都不写（记忆是增强项，写半截比不写更糟）。
     */
    private List<MemoryCandidate> extractCandidates(String input) {
        var prompt = clients.utilityClient().prompt()
                .system(promptTemplateService.systemPrompt(SceneCode.MEMORY))
                .options(sceneModelResolver.memoryOptionsBuilder())
                .user(input);
        if (!aiProperties.getMemory().isStructuredOutput()) {
            return parse(prompt.call().content());
        }
        MemoryExtractionDtos.MemoryPayload payload = prompt
                .advisors(a -> a.advisors(STRUCTURED_OUTPUT_ADVISOR))
                .call()
                .entity(MemoryExtractionDtos.MemoryPayload.class);
        return toCandidates(payload);
    }

    /** 把结构化结果转成内部候选：过滤空 key/空内容，同 key 取置信度更高者（与自由文本路径同规则）。 */
    private static List<MemoryCandidate> toCandidates(MemoryExtractionDtos.MemoryPayload payload) {
        if (payload == null || payload.memories() == null || payload.memories().isEmpty()) {
            return List.of();
        }
        List<MemoryCandidate> raw = new ArrayList<>(payload.memories().size());
        for (MemoryExtractionDtos.MemoryItemPayload item : payload.memories()) {
            if (item == null || !StringUtils.hasText(item.key()) || !StringUtils.hasText(item.text())) {
                continue;
            }
            raw.add(new MemoryCandidate(item.key().trim(), item.text().trim(),
                    item.confidence() == null ? 0 : item.confidence()));
        }
        return dedupeByKey(raw);
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
            List<MemoryCandidate> collected = new ArrayList<>(list.size());
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
                collected.add(candidate);
            }
            return dedupeByKey(collected);
        } catch (RuntimeException e) {
            log.warn("长期记忆抽取结果解析失败（本次不写任何记忆）长度={}", json.length(), e);
            return List.of();
        }
    }

    /**
     * 同 key 只留置信度最高的那条（提示词已要求同 key 只输出一条，这里是防御）。
     * 两条路径（结构化 / 自由文本）都必须走这里，否则"哪条覆盖哪条"会在两处各写一遍。
     */
    private static List<MemoryCandidate> dedupeByKey(List<MemoryCandidate> candidates) {
        Map<String, MemoryCandidate> byKey = new LinkedHashMap<>();
        for (MemoryCandidate candidate : candidates) {
            MemoryCandidate existing = byKey.get(candidate.key());
            if (existing == null || candidate.confidence() > existing.confidence()) {
                byKey.put(candidate.key(), candidate);
            }
        }
        return new ArrayList<>(byKey.values());
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