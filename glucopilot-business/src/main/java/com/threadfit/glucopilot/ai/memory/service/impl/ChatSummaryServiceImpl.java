package com.threadfit.glucopilot.ai.memory.service.impl;

import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.core.SceneModelResolver;
import com.threadfit.glucopilot.ai.prompt.service.AiPromptTemplateService;
import com.threadfit.glucopilot.ai.support.ChatRoundGuard;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
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
 *   <li><b>单飞</b>：同一会话同时只跑一个摘要任务（复用 {@link ChatRoundGuard}，与"一轮只生成一次"同一套机制），
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
    private static final String SUMMARY_KEY_PREFIX = "chat-summary:";

    /** 摘要为空时的占位（模型明确表示"无可保留信息"时用），避免反复触发同一个空摘要任务。 */
    private static final String EMPTY_SUMMARY = "无可保留信息";

    private final ChatClient summaryClient;

    private final ChatSessionMapper chatSessionMapper;

    private final ChatMessageMapper chatMessageMapper;

    private final AiPromptTemplateService promptTemplateService;

    /** 摘要请求的 options（关思维链、输出封顶）由解析器统一给出，避免此处出现 provider 专有类型。 */
    private final SceneModelResolver sceneModelResolver;

    private final AIProperties aiProperties;

    private final ChatRoundGuard summaryGuard;

    private final Executor summaryExecutor;

    public ChatSummaryServiceImpl(@Qualifier("summaryChatClient") @Nullable ChatClient summaryClient,
                              ChatSessionMapper chatSessionMapper,
                              ChatMessageMapper chatMessageMapper,
                              AiPromptTemplateService promptTemplateService,
                              SceneModelResolver sceneModelResolver,
                              AIProperties aiProperties,
                              ChatRoundGuard roundGuard,
                              @Qualifier("chatSummaryExecutor") Executor summaryExecutor) {
        this.summaryClient = summaryClient;
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.promptTemplateService = promptTemplateService;
        this.sceneModelResolver = sceneModelResolver;
        this.aiProperties = aiProperties;
        this.summaryExecutor = summaryExecutor;
        // 直接用容器里那个 ChatRoundGuard（5 分钟过期接管）：它的语义就是"同一件事同时只跑一个"。
        // 注意它只是**尽力而为**——真正保证正确性的是 updateSummary 的"指针只前进"条件：
        // 即使两个摘要任务并发跑完，晚到的那次也会因为 upto 不比库里大而被丢弃。
        this.summaryGuard = roundGuard;
    }

    /**
     * 本轮回复落库后调用：如果"已经有消息落到窗口之外"，就异步把这段压缩进摘要。
     * <p>
     * <b>不阻塞、不抛异常</b>：这个方法在流式收尾的回调里被调用，任何异常都不该影响这一轮的结果。
     */
    public void maybeSummarizeAsync(Long userId, Long sessionId) {
        if (summaryClient == null || sessionId == null) {
            return;
        }
        String key = SUMMARY_KEY_PREFIX + sessionId;
        if (!summaryGuard.tryAcquire(key)) {
            log.debug("该会话已有摘要任务在跑，跳过 sessionId={}", sessionId);
            return;
        }
        try {
            summaryExecutor.execute(() -> {
                try {
                    runSummarize(userId, sessionId);
                } finally {
                    summaryGuard.release(key);
                }
            });
        } catch (RuntimeException e) {
            // 线程池满了/已关闭：释放占位并记日志，绝不让它影响本轮回复
            summaryGuard.release(key);
            log.warn("摘要任务提交失败（忽略）sessionId={}", sessionId, e);
        }
    }

    /**
     * 真正执行一次压缩任务（包级可见，供单测同步调用）。
     *
     * @return 是否成功写入摘要
     */
    boolean runSummarize(Long userId, Long sessionId) {
        if (summaryClient == null) {
            return false;
        }
        try {
            String existing = chatSessionMapper.selectContextSummary(sessionId);
            List<ChatMessage> candidates = summarizableMessages(sessionId);
            if (candidates.isEmpty()) {
                return false;
            }
            String input = buildInput(existing, candidates);
            if (!StringUtils.hasText(input)) {
                return false;
            }
            String summary = callModel(input);
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
    private String callModel(String input) {
        String system = promptTemplateService.systemPrompt(SceneCode.SUMMARY);
        String content = summaryClient.prompt()
                .system(system)
                // 关思维链 + 输出封顶：摘要要"快"而不是"想得多"，详见 SceneModelResolver.summaryOptionsBuilder
                .options(sceneModelResolver.summaryOptionsBuilder())
                .user(input)
                .call()
                .content();
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

    /** 当前是否有摘要任务在跑（E2E/单测断言用）。 */
    public boolean inFlight() {
        return summaryGuard.inFlightCount() > 0;
    }

    /** 供 E2E 断言：摘要场景当前用的提示词来源。 */
    public Map<String, String> debugInfo() {
        return Map.of("scene", SceneCode.SUMMARY.getCode(),
                "promptSource", promptTemplateService.source(SceneCode.SUMMARY));
    }

    /** 供单测/排查：按主键直读某会话的摘要（绕过装配器）。 */
    String rawSummary(Long sessionId) {
        return chatSessionMapper.selectContextSummary(sessionId);
    }
}
