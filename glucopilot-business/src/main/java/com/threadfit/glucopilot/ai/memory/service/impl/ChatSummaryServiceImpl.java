package com.threadfit.glucopilot.ai.memory.service.impl;

import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.config.properties.AIProperties;
import com.threadfit.glucopilot.ai.client.AiChatClientRegistry;
import com.threadfit.glucopilot.ai.scene.SceneCode;
import com.threadfit.glucopilot.ai.routing.SceneModelResolver;
import com.threadfit.glucopilot.ai.prompt.service.AiPromptTemplateService;
import com.threadfit.glucopilot.ai.support.AiCallContext;
import com.threadfit.glucopilot.ai.support.lock.AiConcurrencyPolicy;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import com.threadfit.glucopilot.ai.memory.service.ChatSummaryService;

/**
 * 会话滚动摘要（② 期）。
 *
 * <h2>它解决什么问题</h2>
 * token 预算一旦生效，早期轮次就会被裁掉——用户说"刚才那顿饭呢"时模型已经看不见了。
 * 摘要把这些"已经不进窗口"的内容压成一段事实描述，作为附加上下文留在 prompt 里，
 * 让长期对话不至于失忆，同时不把原文全塞回去（那正是预算要防的事）。
 *
 * <h2>异步、幂等、只前进</h2>
 * <ul>
 *   <li><b>异步</b>：由独立线程池执行，用户不等它（一轮回复已经花了几秒，再串一次模型调用不可接受）；</li>
 *   <li><b>合并</b>：同一会话同时只跑一个摘要任务（{@link AiConcurrencyPolicy#markSummaryRunning}），
 *       否则两轮回复后可能同时触发两次压缩，后写的那次会覆盖先写的；</li>
 *   <li><b>只前进</b>：写回时带 {@code summary_upto_msg_id <} 条件（见 Mapper），慢任务不会把指针拉回去
 *       ——那会导致同一段原文被反复压缩，越压越糊。</li>
 * </ul>
 *
 * <h2>失败的处理</h2>
 * 任何一步失败（模型报错、返回为空、写库失败）都只记日志，<b>不推进指针、不写半截摘要</b>：
 * 下一轮窗口里仍是原文，用户感知不到这次失败；而写进半截摘要才是真的伤——它会永久污染后续上下文。
 */
@Slf4j
@Service
public class ChatSummaryServiceImpl implements ChatSummaryService {

    /** 单飞键前缀：与"一轮生成"的 chat-round: 区分开。 */

    /** 摘要为空时的占位（模型明确表示"无可保留信息"时用），避免反复触发同一个空摘要任务。 */
    private static final String EMPTY_SUMMARY = "无可保留信息";

    /** 摘要用的「裸」文本客户端：不带人设、记忆与工具，与对话链路刻意分开。 */
    private final AiChatClientRegistry clients;

    private final ChatSessionMapper chatSessionMapper;

    private final ChatMessageMapper chatMessageMapper;

    private final AiPromptTemplateService promptTemplateService;

    /** 摘要请求的 options（关思维链、输出封顶）由解析器统一给出，避免此处出现 provider 专有类型。 */
    private final SceneModelResolver sceneModelResolver;

    private final AIProperties aiProperties;

    private final AiConcurrencyPolicy concurrency;

    /** 本实例正在跑的任务数（合并标记是全局的，但"在跑"只有本进程知道）。 */
    private final AtomicInteger running = new AtomicInteger();

    private final Executor summaryExecutor;

    public ChatSummaryServiceImpl(AiChatClientRegistry clients,
                              ChatSessionMapper chatSessionMapper,
                              ChatMessageMapper chatMessageMapper,
                              AiPromptTemplateService promptTemplateService,
                              SceneModelResolver sceneModelResolver,
                              AIProperties aiProperties,
                              AiConcurrencyPolicy concurrency,
                              @Qualifier("chatSummaryExecutor") Executor summaryExecutor) {
        this.clients = clients;
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.promptTemplateService = promptTemplateService;
        this.sceneModelResolver = sceneModelResolver;
        this.aiProperties = aiProperties;
        this.summaryExecutor = summaryExecutor;
        // 用合并标记而不是互斥锁：同一会话的第二个摘要任务不该报错，它只是"没必要再跑一遍"。
        // 注意它只是**尽力而为**——真正保证正确性的是 updateSummary 的"指针只前进"条件：
        // 即使两个摘要任务并发跑完，晚到的那次也会因为 upto 不比库里大而被丢弃。
        this.concurrency = concurrency;
    }

    /**
     * 本轮回复落库后调用：如果"已经有消息落到窗口之外"，就异步把这段压缩进摘要。
     * <p>
     * <b>不阻塞、不抛异常</b>：这个方法在流式收尾的回调里被调用，任何异常都不该影响这一轮的结果。
     */
    @Override
    public void maybeSummarizeAsync(Long userId, Long sessionId) {
        if (!clients.utilityAvailable() || sessionId == null) {
            return;
        }
        if (!concurrency.markSummaryRunning(sessionId)) {
            log.debug("该会话已有摘要任务在跑，跳过 sessionId={}", sessionId);
            return;
        }
        try {
            summaryExecutor.execute(() -> {
                running.incrementAndGet();
                try {
                    runSummarize(userId, sessionId);
                } finally {
                    running.decrementAndGet();
                    concurrency.finishSummary(sessionId);
                }
            });
        } catch (RuntimeException e) {
            // 线程池满了/已关闭：释放标记并记日志，绝不让它影响本轮回复
            concurrency.finishSummary(sessionId);
            log.warn("摘要任务提交失败（忽略）sessionId={}", sessionId, e);
        }
    }

    /**
     * 真正执行一次压缩任务（包级可见，供单测同步调用）。
     *
     * @return 是否成功写入摘要
     */
    boolean runSummarize(Long userId, Long sessionId) {
        if (!clients.utilityAvailable()) {
            return false;
        }
        try {
            String existing = chatSessionMapper.selectContextSummary(sessionId);
            List<ChatMessage> candidates = summarizableMessages(sessionId);
            if (candidates.isEmpty()) {
                return false;
            }
            String skipReason = skipReason(sessionId, candidates);
            if (skipReason != null) {
                // 攒不够就该让它继续攒：每一轮都压一次既不省钱，还会把摘要块反复重写、
                // 连带废掉后面整段历史的前缀缓存（实测：摘要重写的那一轮命中率 24%，下一轮回到 93%）
                log.debug("暂不摘要（{}）sessionId={} 候选={}条", skipReason, sessionId, candidates.size());
                return false;
            }
            String input = buildInput(existing, candidates);
            if (!StringUtils.hasText(input)) {
                return false;
            }
            String summary = callModel(input, userId, sessionId);
            if (!StringUtils.hasText(summary)) {
                log.warn("摘要模型返回空内容，本次不写库 sessionId={}", sessionId);
                return false;
            }
            String limited = limit(summary, aiProperties.getMemory().getSummaryMaxChars());
            Long upto = candidates.get(candidates.size() - 1).getId();
            int updated = chatSessionMapper.updateSummary(sessionId, limited, upto);
            if (updated == 0) {
                // 指针已经更靠前（有更快的任务写过了）：本次结果丢弃即可
                log.debug("摘要未写入（指针已前进）sessionId={} upto={}", sessionId, upto);
                return false;
            }
            log.info("会话摘要已更新 userId={} sessionId={} 压缩消息={} 摘要={}字 覆盖到消息={}",
                    userId, sessionId, candidates.size(), limited.length(), upto);
            return true;
        } catch (RuntimeException e) {
            log.warn("会话摘要任务失败（保留原文窗口，下一轮再试）sessionId={}", sessionId, e);
            return false;
        }
    }

    /**
     * 找出"已经被窗口挤出去、且还没被摘要覆盖"的消息（时间正序）。
     * <p>
     * 判据与读取侧保持同一套配置：保留最近 {@code keep-recent-messages} 条原文（那是预算的保底），
     * 其余更早的部分才是摘要的候选。用"保留条数"而不是重算预算，好处是<b>这里的判断永远偏保守</b>——
     * 最坏情况是某段内容既进了摘要又还在窗口里（重复一次），而不会出现"两头都没有"的空洞。
     */
    private List<ChatMessage> summarizableMessages(Long sessionId) {
        int keep = Math.max(aiProperties.getMemory().getKeepRecentMessages(), 0);
        // 多取一些：候选是"更早的那些"，条数上限用 maxFetch 兜住
        List<ChatMessage> recent = chatMessageMapper.selectRecent(sessionId, Integer.MAX_VALUE,
                keep + aiProperties.getMemory().getMaxFetch());
        if (recent.size() <= keep) {
            return List.of();
        }
        // selectRecent 是倒序（最新在前）：去掉最近 keep 条，剩下的就是候选；再反转成时间正序
        List<ChatMessage> candidates = new ArrayList<>(recent.subList(keep, recent.size()));
        java.util.Collections.reverse(candidates);
        Long covered = coveredUpTo(sessionId);
        List<ChatMessage> pending = new ArrayList<>(candidates.size());
        for (ChatMessage message : candidates) {
            if (covered == null || message.getId() > covered) {
                pending.add(message);
            }
        }
        return pending;
    }

    /** 已覆盖到的消息 ID（null = 还没摘要过）。 */
    private Long coveredUpTo(Long sessionId) {
        // 摘要与指针在同一行，这里只取指针；selectContextSummary 为了省带宽只取了正文，
        // 因此指针用一个轻量查询单独取（同一行，代价可忽略）。
        Long upto = chatSessionMapper.selectSummaryUptoMsgId(sessionId);
        return upto;
    }

    /**
     * 判断"这一轮值不值得跑摘要"；返回 null 表示值得跑，否则返回跳过的原因（用于日志）。
     *
     * <p>三道门：</p>
     * <ol>
     *   <li><b>条数</b>：候选 ≥ {@code summary-min-pending-messages}（默认 12，≈6 轮对话）；</li>
     *   <li><b>字数</b>：候选的正文合计 ≥ {@code summary-min-pending-chars}（默认 1500）；</li>
     *   <li><b>间隔</b>：距上次成功摘要 ≥ {@code summary-min-interval-seconds}（默认 60 秒）。</li>
     * </ol>
     *
     * <p>为什么条数与字数取"或"：语音转写的长消息 2 条就可能过 1500 字，等满 12 条太久；
     * 反过来纯寒暄的 20 条短消息也压不出什么东西，字数门挡掉它。
     * 两个门分别对应"消息多但都短"与"消息少但都长"两种真实分布。</p>
     *
     * <p>为什么还要间隔门：条数/字数门可能在几十秒内被连续突破（用户连发多条），
     * 而刚写好的摘要下一轮缓存还没被复用，再写一次纯属浪费。</p>
     */
    private String skipReason(Long sessionId, List<ChatMessage> candidates) {
        AIProperties.MemoryProperties memory = aiProperties.getMemory();
        int minMessages = memory.getSummaryMinPendingMessages();
        int minChars = memory.getSummaryMinPendingChars();
        int pendingChars = 0;
        for (ChatMessage message : candidates) {
            String text = replayText(message);
            if (text != null) {
                pendingChars += text.length();
            }
        }
        boolean enoughMessages = minMessages <= 0 || candidates.size() >= minMessages;
        boolean enoughChars = minChars <= 0 || pendingChars >= minChars;
        if (!enoughMessages && !enoughChars) {
            return "候选 " + candidates.size() + " 条/" + pendingChars + " 字，未达 "
                    + minMessages + " 条或 " + minChars + " 字的门";
        }
        int minIntervalSeconds = memory.getSummaryMinIntervalSeconds();
        // 窗口即将溢出让位（2026-11-19 与需求方确认）：装配器给"摘要指针之后的未摘要消息"留了必留带，
        // 但它自身受 window-messages 与预算两道上限约束。一旦 pending 逼近窗口容量，
        // 再守 60 秒间隔就会让必留带被挤掉（= 空洞）。此时按"数据优先"立刻摘要：
        // 代价只是突发连发时摘要调用比 60 秒一次更频繁，比丢消息便宜得多。
        boolean urgent = isWindowAboutToOverflow(candidates.size(), memory);
        if (minIntervalSeconds > 0 && !urgent) {
            java.time.LocalDateTime last = chatSessionMapper.selectSummaryUpdatedAt(sessionId);
            if (last != null) {
                long elapsed = java.time.Duration.between(last, java.time.LocalDateTime.now()).getSeconds();
                // 负数说明库与应用的钟不一致（跨时区/回拨）：这种情况不该把摘要永久卡住，放行
                if (elapsed >= 0 && elapsed < minIntervalSeconds) {
                    return "距上次摘要仅 " + elapsed + " 秒（下限 " + minIntervalSeconds + " 秒）";
                }
            }
        }
        return null;
    }

    /**
     * 候选条数是否已经逼近窗口容量（逼近即让间隔门让位）。
     *
     * <p>判据推导：「未摘要条数 = 候选 + keep-recent」必须装得进 {@code window-messages}，
     * 否则装配器就要么丢必留带（空洞）、要么超条数上限。留 1 条余量给"这一轮刚进来的消息"，
     * 因此阈值取 {@code windowMessages − keepRecent − 1}。
     *
     * <p>配置本身不自洽时（阈值 ≤0，例如 window 比 keep 还小）返回 true：宁可多调一次摘要，
     * 也不要在自相矛盾的配置下持续丢消息。
     */
    private static boolean isWindowAboutToOverflow(int pendingMessages, AIProperties.MemoryProperties memory) {
        int threshold = memory.getWindowMessages() - memory.getKeepRecentMessages() - 1;
        if (threshold <= 0) {
            return true;
        }
        return pendingMessages >= threshold;
    }

    /**
     * 拼摘要输入：已有摘要 + 新增原文。
     * <p>
     * 把已有摘要一并送进去（而不是分两次摘要再拼接），是为了让模型做<b>合并去重</b>——
     * 拼接两次摘要会得到重复且互相矛盾的段落。
     */
    private String buildInput(String existing, List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(existing)) {
            sb.append("【已有摘要（请与下面的新对话合并去重后输出一段摘要）】\n")
                    .append(existing.trim()).append("\n\n");
        }
        sb.append("【需要压缩的对话原文（时间正序）】\n");
        int budget = Math.max(aiProperties.getMemory().getSummaryInputMaxChars(), 500);
        // 超长时只取最近的部分：越新的原文对"接下来怎么回答"越重要
        List<String> lines = new ArrayList<>();
        for (ChatMessage message : messages) {
            String text = replayText(message);
            if (!StringUtils.hasText(text)) {
                continue;
            }
            String role = message.getMsgRole() != null && message.getMsgRole() == CodeEnum.ChatMsgRole.USER.getCode()
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

    /** 与记忆回放同一套口径：原话优先，图片消息用摘要兜底并标注。 */
    private static String replayText(ChatMessage row) {
        if (StringUtils.hasText(row.getContentText())) {
            return row.getContentText();
        }
        if (StringUtils.hasText(row.getMediaCaption())) {
            return "[用户发来的图片，内容摘要] " + row.getMediaCaption();
        }
        return null;
    }

    /**
     * 调用摘要场景的模型。
     * <p>
     * 用<b>独立的 ChatClient</b>（{@code summaryChatClient}）：对话 client 上挂着记忆注入与
     * CHAT 提示词，用它做摘要会把整段历史再塞一遍、还会把"控糖助手"人设注入摘要里。
     * 系统提示词按请求取（与视觉链路同一套做法，后台改完立即生效）。
     */
    private String callModel(String input, Long userId, Long sessionId) {
        String system = promptTemplateService.systemPrompt(SceneCode.SUMMARY);
        // 账单归属：摘要是一轮对话之外的第二笔账，报表要能单独看到它的成本
        AiCallContext.Call callContext = new AiCallContext.Call(SceneCode.SUMMARY, userId, sessionId);
        String content = AiCallContext.run(callContext, () -> clients.utilityClient().prompt()
                .system(system)
                // 关思维链 + 输出封顶：摘要要"快"而不是"想得多"，详见 SceneModelResolver.summaryOptionsBuilder
                .options(sceneModelResolver.summaryOptionsBuilder())
                .user(input)
                .call()
                .content());
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String trimmed = content.trim();
        // 模型偶尔会把"无可保留信息"写成一句话；统一成固定占位，避免下次又把它当新信息送进去
        return trimmed.length() > 200 && trimmed.contains("无可保留信息") && trimmed.length() < 220
                ? EMPTY_SUMMARY : trimmed;
    }

    private static String limit(String text, int maxChars) {
        int limit = Math.max(maxChars, 100);
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    /**
     * 当前是否有摘要任务在跑（E2E/单测断言用）。
     *
     * <p>注意：这是**本实例**的视角。合并标记在 Redis 里是全局的，但"正在跑"这件事
     * 只有当前进程真的在跑才算——别的实例在跑对我们没有意义，也没法从标记反查。
     */
    @Override
    public boolean inFlight() {
        return running.get() > 0;
    }

    /** 供 E2E 断言：摘要场景当前用的提示词来源。 */
    @Override
    public Map<String, String> debugInfo() {
        return Map.of("scene", SceneCode.SUMMARY.getCode(),
                "promptSource", promptTemplateService.source(SceneCode.SUMMARY));
    }

    /** 供单测/排查：按主键直读某会话的摘要（绕过装配器）。 */
    String rawSummary(Long sessionId) {
        return chatSessionMapper.selectContextSummary(sessionId);
    }
}
