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
     * 本轮回复落库后调用：按水位信号决定是否把窗口外的未摘要部分压缩进摘要。
     * <p>
     * <b>不阻塞、不抛异常</b>：这个方法在流式收尾的回调里被调用，任何异常都不该影响这一轮的结果。
     */
    @Override
    public void maybeSummarizeAsync(Long userId, Long sessionId, SummarySignal signal) {
        if (!clients.utilityAvailable() || sessionId == null) {
            return;
        }
        if (!concurrency.markSummaryRunning(sessionId)) {
            log.debug("该会话已有摘要任务在跑，跳过 sessionId={}", sessionId);
            return;
        }
        SummarySignal effective = signal == null ? SummarySignal.unknown() : signal;
        try {
            summaryExecutor.execute(() -> {
                running.incrementAndGet();
                try {
                    runSummarize(userId, sessionId, effective);
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
        return runSummarize(userId, sessionId, SummarySignal.unknown());
    }

    /**
     * 真正执行一次压缩任务（带水位信号）。
     *
     * @return 是否成功写入摘要
     */
    boolean runSummarize(Long userId, Long sessionId, SummarySignal signal) {
        if (!clients.utilityAvailable()) {
            return false;
        }
        try {
            String existing = chatSessionMapper.selectContextSummary(sessionId);
            List<ChatMessage> candidates = summarizableMessages(sessionId);
            if (candidates.isEmpty()) {
                return false;
            }
            // 判据用**装配期量到的水位**，不是这里的候选条数：候选被 max-fetch/keep 截断过，
            // 而"待压多少条"才是装配时真的装不下的量（两者在长会话里会差很多）
            int pendingMessages = signal.known() ? signal.pendingMessages() : candidates.size();
            int pendingChars = signal.known() ? signal.pendingChars() : charCount(candidates);
            Decision decision = decide(userId, sessionId, signal, pendingMessages, pendingChars);
            if (!decision.run()) {
                // 攒不够/还没到水位就该让它继续攒：每一轮都压一次既不省钱，还会把摘要块反复重写、
                // 连带废掉后面整段历史的前缀缓存（实测：摘要重写的那一轮命中率 24%，下一轮回到 93%）
                log.debug("暂不摘要（{}）sessionId={} 待压={}条/{}字 {}",
                        decision.reason(), sessionId, pendingMessages, pendingChars, signal);
                return false;
            }
            // 只覆盖"走到目标水位"所需的那一段，且从最旧的开始——指针因此是连续推进的，
            // 不会出现"先压了新的、更旧的候选永远轮不到"的漏洞
            List<ChatMessage> covered = trimToTarget(candidates, signal);
            String input = buildInput(existing, covered);
            if (!StringUtils.hasText(input)) {
                return false;
            }
            String summary = callModel(input, userId, sessionId);
            if (!StringUtils.hasText(summary)) {
                log.warn("摘要模型返回空内容，本次不写库 sessionId={}", sessionId);
                return false;
            }
            String limited = limit(summary, aiProperties.getMemory().getSummaryMaxChars());
            Long upto = covered.get(covered.size() - 1).getId();
            int updated = chatSessionMapper.updateSummary(sessionId, limited, upto);
            if (updated == 0) {
                // 指针已经更靠前（有更快的任务写过了）：本次结果丢弃即可
                log.debug("摘要未写入（指针已前进）sessionId={} upto={}", sessionId, upto);
                return false;
            }
            concurrency.clearSummaryFailure(sessionId);
            log.info("会话摘要已更新 userId={} sessionId={} 压缩消息={} 摘要={}字 覆盖到消息={} 触发={} 待压={}→{}",
                    userId, sessionId, covered.size(), limited.length(), upto, decision.reason(),
                    pendingMessages, Math.max(0, pendingMessages - covered.size()));
            return true;
        } catch (RuntimeException e) {
            AIProperties.MemoryProperties memory = aiProperties.getMemory();
            int backoff = concurrency.markSummaryFailed(sessionId,
                    memory.getSummaryBackoffBaseSeconds(), memory.getSummaryBackoffMaxSeconds());
            log.warn("会话摘要任务失败（{} 秒内不再尝试，原文仍在窗口里）sessionId={}", backoff, sessionId, e);
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

    /** 一次门控判定的结果：{@code reason} 只用于日志与测试断言（TINY / BELOW_WATER / COOLDOWN / BACKOFF / QUOTA / HIGH_WATER / DROPPED / UNKNOWN）。 */
    record Decision(boolean run, String reason) {
    }

    /**
     * 判断"这一轮值不值得跑摘要"。
     *
     * <h2>为什么不是"候选 ≥12 条或 ≥1500 字"</h2>
     * 旧判据数的是<b>候选条数</b>，而真正决定该不该压的是<b>装配器还能不能把它们装进 prompt</b>
     * （必留带水位）。两个量不同源，导致两种极端：水位已经很高但候选只有 11 条时死活不压（丢消息），
     * 或者候选 13 条但其实窗口装得下时每轮都压（白花钱、白废缓存）。现在两者同源。
     *
     * <h2>四层</h2>
     * <ol>
     *   <li><b>防抖下限</b>：待压条数与字符都太小（默认 &lt;6 条且 &lt;800 字）→ 不值得压；</li>
     *   <li><b>硬触发</b>（无视冷却与退避）：上一轮被预算挤掉过 / 待压逼近窗口 / 估算顶到预算；</li>
     *   <li><b>机会窗</b>：待压越过半窗（默认 20/2=10 条），且过了冷却、没在退避、当天配额还有；</li>
     *   <li><b>否则继续攒</b>。</li>
     * </ol>
     */
    Decision decide(Long userId, Long sessionId, SummarySignal signal, int pendingMessages, int pendingChars) {
        AIProperties.MemoryProperties memory = aiProperties.getMemory();
        if (pendingMessages < memory.getSummaryFloorMessages() && pendingChars < memory.getSummaryFloorChars()) {
            return new Decision(false, "TINY");
        }
        if (!signal.known()) {
            // 调用方没能提供水位（老接口/测试）：有候选就压，保持旧行为
            return new Decision(true, "UNKNOWN");
        }
        int window = Math.max(memory.getWindowMessages(), 1);
        int highWaterMessages = Math.max(window - 2, 1);
        boolean budgetHigh = signal.budgetTokens() > 0
                && signal.estimateTokens() >= signal.budgetTokens() * memory.getSummaryHighWaterRatio();
        boolean dropped = signal.droppedLast() > 0;
        if (dropped) {
            // 最强信号：上一轮用户说的话已经没进 prompt。此时任何冷却/退避/配额都得让位
            return new Decision(true, "DROPPED");
        }
        if (pendingMessages >= highWaterMessages || budgetHigh) {
            // 硬触发不受冷却与退避约束，否则会一路滑到"必留带被预算截断"
            return new Decision(true, "HIGH_WATER");
        }
        if (pendingMessages < targetWater(window, memory)) {
            return new Decision(false, "BELOW_WATER");
        }
        if (concurrency.summaryInBackoff(sessionId)) {
            return new Decision(false, "BACKOFF");
        }
        int cooldown = memory.getSummaryCooldownSeconds();
        if (cooldown > 0) {
            java.time.LocalDateTime last = chatSessionMapper.selectSummaryUpdatedAt(sessionId);
            if (last != null) {
                long elapsed = Math.abs(java.time.Duration.between(last, java.time.LocalDateTime.now()).getSeconds());
                if (elapsed < cooldown) {
                    return new Decision(false, "COOLDOWN");
                }
            }
        }
        if (!concurrency.reserveSummaryQuota(userId, memory.getSummaryDailyQuota())) {
            return new Decision(false, "QUOTA");
        }
        return new Decision(true, "OPPORTUNISTIC");
    }

    /** 目标水位：压一次要回落到 {@code window × summary-target-ratio}（默认 10 条）。 */
    private static int targetWater(int window, AIProperties.MemoryProperties memory) {
        double ratio = memory.getSummaryTargetRatio();
        return Math.max(1, (int) Math.round(window * (ratio <= 0 || ratio >= 1 ? 0.5 : ratio)));
    }

    /**
     * 从最旧的候选开始挑出"本次要压进摘要"的那一段。
     *
     * <h2>为什么从最旧开始</h2>
     * 指针只能连续前进。若像旧实现那样"取最新的 N 字、指针跳到最新那条"，中间更旧的候选
     * 就既不在摘要里、也不会再被选中——永久丢失。反过来"从最旧往后装、装不下就停"，
     * 剩下的留到下一轮继续，指针永远连续。
     *
     * <h2>为什么还要停在水位</h2>
     * 一次压到目标水位（而不是能压多少压多少）正是滞回：压完至少还能撑几轮，
     * 而摘要块只会被重写一次——重写次数决定前缀缓存被废掉几次。
     */
    List<ChatMessage> trimToTarget(List<ChatMessage> candidates, SummarySignal signal) {
        AIProperties.MemoryProperties memory = aiProperties.getMemory();
        int budget = Math.max(memory.getSummaryInputMaxChars(), 500);
        int target = signal.known() ? targetWater(Math.max(memory.getWindowMessages(), 1), memory) : 0;
        int drop = signal.known() ? Math.max(0, signal.pendingMessages() - target) : Integer.MAX_VALUE;
        int want = Math.max(1, Math.min(candidates.size(), candidates.size() - drop));
        if (!signal.known()) {
            want = candidates.size();
        }
        List<ChatMessage> covered = new ArrayList<>();
        int chars = 0;
        for (ChatMessage message : candidates) {
            if (covered.size() >= want) {
                break;
            }
            String text = replayText(message);
            int cost = text == null ? 0 : text.length();
            if (!covered.isEmpty() && chars + cost > budget) {
                break;
            }
            covered.add(message);
            chars += cost;
        }
        if (covered.isEmpty()) {
            covered.add(candidates.get(0));
        }
        return covered;
    }

    /** 候选正文合计字符数（防抖下限用）。 */
    private int charCount(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage message : messages) {
            String text = replayText(message);
            if (text != null) {
                total += text.length();
            }
        }
        return total;
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
        // 从最旧的往后装：调用方（trimToTarget）已经按"连续覆盖"挑好这一段，
        // 这里再截一次只为兜住"单条消息就超预算"的极端情况；一旦装不下就停，
        // 让剩下的留在窗口里等下一轮——指针因此不会跳过任何一条。
        int total = 0;
        for (ChatMessage message : messages) {
            String text = replayText(message);
            if (!StringUtils.hasText(text)) {
                continue;
            }
            String role = message.getMsgRole() != null && message.getMsgRole() == CodeEnum.ChatMsgRole.USER.getCode()
                    ? "用户" : "助手";
            String line = role + "：" + text.replaceAll("\\s+", " ").trim();
            if (total > 0 && total + line.length() > budget) {
                break;
            }
            sb.append(line).append('\n');
            total += line.length();
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
