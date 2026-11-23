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
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.exception.BizException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import com.threadfit.glucopilot.ai.memory.controller.UserChatMemoryController;
import com.threadfit.glucopilot.ai.memory.dto.MemoryExtractionDtos;
import com.threadfit.glucopilot.ai.memory.dto.UserChatMemoryDtos;
import com.threadfit.glucopilot.ai.memory.entity.ChatUserMemoryEntity;
import com.threadfit.glucopilot.ai.memory.mapper.ChatUserMemoryKeyCount;
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
     * 注入时每个类别最多带几条（V71 之后一类可以有多条）。
     * <p>
     * 三条的取舍：一条常常不够（"不吃香菜 + 不吃辣 + 海鲜过敏"是三次对话分别记下的），
     * 但十条会让一行变成一段话，把历史消息的预算挤掉。更多的条目留给用户自己的记忆页——
     * 那一页的用途是"看清全貌"，而注入的用途是"别答错"。
     */
    static final int MAX_ITEMS_PER_KEY_INJECTED = 3;

    /** 空白折叠：关键词检索前把连续空白压成一个空格，避免「不吃  香菜」搜不到「不吃 香菜」。 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

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


    /**
     * 关键词兜底触发的抽取原因（{@link #looksExplicit} 命中，见 {@code ChatCompletionServiceImpl}）。
     * <p>
     * 它和工具路径的区别：工具是模型主动调用、只记当轮一句；这里是「没有 tool calling 的部署」下
     * 用关键词猜意图，所以窗口收窄到最近几条，避免每轮闲聊都触发一次整段复盘。
     */
    static final String REASON_KEYWORD_FALLBACK = "explicit";

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
    private final AiConcurrencyPolicy concurrency;

    /** 本实例正在跑的抽取任务数（用于 inFlight 断言）。 */
    private final java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger();
    private final Executor executor;

    public UserChatMemoryServiceImpl(AiChatClientRegistry clients,
                                 ChatUserMemoryMapper memoryMapper,
                                 ChatMessageMapper chatMessageMapper,
                                 ChatSessionMapper chatSessionMapper,
                                 AiPromptTemplateService promptTemplateService,
                                 SceneModelResolver sceneModelResolver,
                                 AIProperties aiProperties,
                                 com.threadfit.glucopilot.common.util.JsonUtils jsonUtils,
                                 AiConcurrencyPolicy concurrency,
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
        this.concurrency = concurrency;
        this.executor = executor;
    }

    // ------------------------------------------------------------------ 读取（供上下文装配）

    /**
     * 取要注入上下文的记忆：<b>按类分组</b>，一类一行，最多 {@code ai.memory.inject-max-items} 行。
     * <p>
     * 为什么是「一类一行」而不是「一条一行」：V71 之后同一类下可以有多条（不吃香菜 / 不吃辣 /
     * 海鲜过敏）。如果每条单独占一行，五类事实多起来就会挤掉历史消息与摘要的预算；而分组之后
     * 「饮食忌口：不吃香菜、不吃辣、海鲜过敏」既完整又只占一行的位置。
     * 每类最多取 {@link #MAX_ITEMS_PER_KEY_INJECTED} 条——再多的条目对回答没有增量信息，
     * 完整的清单留给用户自己的记忆页去看。
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
            // 按置信度/更新时间排序取回，多取一些：分组之后需要的行数取决于每类几条，
            // 只取 max 行可能全落在同一类里，导致别的类一条都进不来。
            List<ChatUserMemoryEntity> rows = memoryMapper.selectActiveByUser(userId, max * MAX_ITEMS_PER_KEY_INJECTED);
            Map<String, List<ChatUserMemoryEntity>> grouped = new LinkedHashMap<>();
            for (ChatUserMemoryEntity row : rows) {
                List<ChatUserMemoryEntity> bucket =
                        grouped.computeIfAbsent(row.getMemoryKey(), k -> new ArrayList<>());
                if (bucket.size() < MAX_ITEMS_PER_KEY_INJECTED) {
                    bucket.add(row);
                }
            }
            List<MemoryItem> items = new ArrayList<>(Math.min(grouped.size(), max));
            for (Map.Entry<String, List<ChatUserMemoryEntity>> entry : grouped.entrySet()) {
                if (items.size() >= max) {
                    break;
                }
                List<ChatUserMemoryEntity> bucket = entry.getValue();
                StringBuilder joined = new StringBuilder();
                int confidence = 0;
                for (ChatUserMemoryEntity row : bucket) {
                    if (joined.length() > 0) {
                        joined.append('、');
                    }
                    joined.append(row.getMemoryText().trim());
                    confidence = Math.max(confidence, row.getConfidence() == null ? 100 : row.getConfidence());
                }
                items.add(new MemoryItem(entry.getKey(), joined.toString(), confidence));
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
     *
     * <h2>为什么分页、为什么排序是「类升序 + 时间降序」</h2>
     * 条目不再有界（一类可以有很多条），全量返回会让"我到底被记了什么"变成滚不到底的列表。
     * 排序把同一类放在一起：用户想"把忌口都清掉"时能一眼看完一类；类内按最近更新排序，
     * 因为用户最可能想删的是刚被记错的那条。
     *
     * <h2>为什么还要带 counts</h2>
     * 记忆页顶部是五个分类入口，各自要显示条数；这些数字与"正在第几页、正在筛哪一类"无关，
     * 所以每次加载都顺手带一份全量分类型统计（一条 GROUP BY，见
     * {@code ChatUserMemoryMapper#countActiveGroupByKey}），页面不必为它多开一次请求。
     */
    @Override
    public UserChatMemoryDtos.MemoryPage listOwnForUser(Long userId, String memoryKey, PageQuery query) {
        long pageNum = query == null ? 1 : Math.max(1, query.getPageNum());
        long pageSize = query == null ? 20 : Math.max(1, Math.min(100, query.getPageSize()));
        String key = normalizeKeyFilter(memoryKey);
        if (userId == null) {
            return UserChatMemoryDtos.MemoryPage.builder()
                    .records(List.of())
                    .total(0)
                    .pages(0)
                    .pageNum(pageNum)
                    .pageSize(pageSize)
                    .counts(emptyCounts())
                    .build();
        }
        Page<ChatUserMemoryEntity> page = new Page<>(pageNum, pageSize);
        IPage<ChatUserMemoryEntity> result = memoryMapper.selectPage(page,
                new LambdaQueryWrapper<ChatUserMemoryEntity>()
                        .eq(ChatUserMemoryEntity::getUserId, userId)
                        // 只在真的筛某一类时拼条件：传空串等于「不限类」，不能让 wrapper 生成 memory_key = ''
                        .eq(key != null, ChatUserMemoryEntity::getMemoryKey, key)
                        .orderByAsc(ChatUserMemoryEntity::getMemoryKey)
                        .orderByDesc(ChatUserMemoryEntity::getGmtModified)
                        .orderByDesc(ChatUserMemoryEntity::getId));
        List<UserChatMemoryDtos.MemoryRow> items = new ArrayList<>();
        for (ChatUserMemoryEntity row : result.getRecords()) {
            items.add(UserChatMemoryDtos.MemoryRow.builder()
                    .id(row.getId())
                    .memoryKey(row.getMemoryKey())
                    .memoryText(row.getMemoryText())
                    .confidence(row.getConfidence())
                    .updatedAt(row.getGmtModified())
                    .build());
        }
        long total = result.getTotal();
        long pages = pageSize == 0 ? 0 : (total + pageSize - 1) / pageSize;
        return UserChatMemoryDtos.MemoryPage.builder()
                .records(items)
                .total(total)
                .pages(pages)
                .pageNum(pageNum)
                .pageSize(pageSize)
                .counts(countsOf(userId))
                .build();
    }

    /**
     * 规范化「只看某一类」的参数。
     *
     * <p>空白 → {@code null}（不筛）；不在白名单 → 抛 400。
     * 不做"未知就忽略"的降级：那样页面会显示全部记忆，用户看着是自己点的那一类、
     * 内容却是混的，比直接报错更难查。
     */
    private String normalizeKeyFilter(String memoryKey) {
        String key = memoryKey == null ? null : memoryKey.trim();
        if (key == null || key.isEmpty()) {
            return null;
        }
        if (!ALLOWED_KEYS.contains(key)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "未知的记忆类型：" + key + "（只能是 " + String.join(" / ", ALLOWED_KEYS) + "）");
        }
        return key;
    }

    // ------------------------------------------------------------------ 读取（供读工具）

    /**
     * 读工具的总览视图：可按类与关键词收窄，条数与 {@link #MAX_MODEL_ITEMS} 有关。
     *
     * <h2>为什么单独一次 count，而不是用「查回来的行数」</h2>
     * 读工具的价值一半在「你一共记了多少条」：只给条目，模型会把"我看到 15 条"说成"你一共 15 条"，
     * 这正是注入侧一直存在的毛病（注入有上限、但模型不知道上限存在）。多一次走索引的 count，
     * 换来模型能诚实地说"我记着 27 条，先说最相关的几条"，值得。
     *
     * <h2>为什么多取一条</h2>
     * {@code limit + 1} 只用来判断"还有没有更多"，最后一条不返回——比再查一次 count 便宜，
     * 也比用 {@code total > shown} 判断更稳（筛选条件与分页边界同时变化时不会误判）。
     *
     * <h2>读失败为什么照常抛出</h2>
     * 与 {@link #forInjection} 相反：这里的调用方是"模型主动问了一句"，把异常翻译成结构化错误，
     * 模型可以据此说"我这边查不到"，而不是拿到一个空列表去回答"你没有任何忌口"。
     */
    @Override
    public MemoryOverview overviewForModel(Long userId, String memoryKey, String keyword, int limit) {
        String key = normalizeKeyFilter(memoryKey);
        String kw = normalizeKeyword(keyword);
        int cap = Math.max(1, Math.min(limit, MAX_MODEL_ITEMS));
        if (userId == null) {
            return new MemoryOverview(0, emptyCounts(), List.of(), false);
        }
        List<ChatUserMemoryEntity> rows = memoryMapper.selectActiveFiltered(userId, key, kw, cap + 1);
        boolean truncated = rows.size() > cap;
        List<ChatUserMemoryEntity> shown = truncated ? rows.subList(0, cap) : rows;
        List<MemoryEntry> items = new ArrayList<>(shown.size());
        for (ChatUserMemoryEntity row : shown) {
            String text = row.getMemoryText() == null ? "" : row.getMemoryText().trim();
            items.add(new MemoryEntry(row.getMemoryKey(), text, row.getGmtModified()));
        }
        int total = memoryMapper.countActiveFiltered(userId, key, kw);
        return new MemoryOverview(total, countsOf(userId), items, truncated);
    }

    /**
     * 规范化读工具的关键词：空白 → {@code null}；内部连续空白折成一个空格。
     *
     * <p>长度校验留在工具层（那里才知道"用户输入的问题 vs 模型自己编的参数"该怎么解释），
     * 这里只做「能不能进 SQL」的清洗。
     */
    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String kw = WHITESPACE.matcher(keyword).replaceAll(" ").trim();
        return kw.isEmpty() ? null : kw;
    }

    /**
     * 每类生效条数；五类全部出现（没有记忆的类给 0），未知类按实际数量附在后面。
     *
     * <p>统计失败<b>不</b>让整个列表失败：条数只是入口上的装饰，
     * 列表本身能出来更重要（这里与"读失败照常抛"不冲突——那个说的是条目内容）。
     */
    private Map<String, Integer> countsOf(Long userId) {
        Map<String, Integer> counts = emptyCounts();
        try {
            for (ChatUserMemoryKeyCount row : memoryMapper.countActiveGroupByKey(userId)) {
                if (row == null || row.getMemoryKey() == null) {
                    continue;
                }
                counts.put(row.getMemoryKey(), row.getTotal() == null ? 0 : row.getTotal());
            }
        } catch (RuntimeException e) {
            log.warn("统计长期记忆条数失败（入口数字按 0 显示）userId={}", userId, e);
        }
        return counts;
    }

    /** 五类键按固定顺序零值填满，前端渲染入口时不必自己补。 */
    private static Map<String, Integer> emptyCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String key : ALLOWED_KEYS) {
            counts.put(key, 0);
        }
        return counts;
    }

    /** 用户自助删除全部长期记忆（合规：用户有权清掉系统对他的记忆）。返回删除条数。 */
    @Override
    public int deleteAllForUser(Long userId) {
        if (userId == null) {
            return 0;
        }
        // 没有任何生效条目就直接返回 0：这一刻不许清墓碑。
        // 墓碑是"系统曾经记过什么 + 什么时候被抹掉"的唯一证据，也是冷却闸门的输入；
        // 一次幂等的重复删除请求把它清掉，等于用户点错了两次就把审计痕迹擦掉了。
        if (memoryMapper.countActiveByUser(userId) == 0) {
            log.debug("用户没有生效记忆，跳过清理 userId={}", userId);
            return 0;
        }
        // 先请墓碑：唯一键含 is_deleted（只有 0/1），每个条目的墓碑至多一条。
        // 用户"删掉忌口 → 又说了一次 → 再清空"时，第二次删除会与第一条墓碑撞键（MySQL 1062）。
        // 墓碑的唯一用途是冷却（取 MAX(deleted_at)），清掉更早的那条不影响语义。
        int purged = memoryMapper.purgeTombstonesByUser(userId);
        // ⚠️ 不能用 MP 内置逻辑删除：它只置 is_deleted=1，而 DDL 的 chk_cum_del_consistent 要求
        // 删除态必须同时写 deleted_at，MySQL 8 会直接拒绝（H2 实测复现过）。因此走显式 UPDATE。
        // deleted_by 传 null：这是用户自己行使删除权，不是运营代删，把 C 端 user_id 记进
        // "操作人"列会让管理端的删除留痕把用户和运营混在一起（运营侧的留痕走 ops_audit_log）。
        int deleted = memoryMapper.logicDeleteAllByUser(userId, null);
        log.info("用户自助清除长期记忆 userId={} 条数={} 清理过期墓碑={}", userId, deleted, purged);
        return deleted;
    }

    /**
     * 用户自助清空某一类的全部条目（记忆页每类卡片上的「清空本类」）。
     *
     * <p>键先过白名单：路径参数是客户端传的，不校验就会把"随便什么字符串"变成一次 UPDATE
     * （虽然删不到东西，但日志里会留下噪声，而且用户拼错键时应当看到明确的 400，
     * 而不是"删除成功 0 条"的错觉）。
     *
     * <p>同样先清该类墓碑再置删除位，原因见 {@link #deleteAllForUser}。
     *
     * <p>删除之后由写入侧的条目级冷却负责"别再自动记回来"，见
     * {@link #withinDeletedCooldown(Long, String, String)}。
     */
    @Override
    public int deleteByKeyForUser(Long userId, String memoryKey) {
        if (userId == null) {
            return 0;
        }
        String normalizedKey = memoryKey == null ? "" : memoryKey.trim();
        if (!UserChatMemoryService.ALLOWED_KEYS.contains(normalizedKey)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "未知的记忆类型：" + normalizedKey + "，只能是 diet_taboo / glucose_goal / "
                            + "health_context / habit / preference");
        }
        // 没有生效条目就返回 0，且不清墓碑（理由同 deleteAllForUser：墓碑是审计与冷却的证据）。
        if (memoryMapper.countActiveByKey(userId, normalizedKey) == 0) {
            log.debug("该类没有生效记忆，跳过清理 userId={} key={}", userId, normalizedKey);
            return 0;
        }
        int purged = memoryMapper.purgeTombstonesByKey(userId, normalizedKey);
        int deleted = memoryMapper.logicDeleteByKey(userId, normalizedKey, null);
        log.info("用户自助清空一类长期记忆 userId={} key={} 条数={} 清理过期墓碑={}",
                userId, normalizedKey, deleted, purged);
        return deleted;
    }

    /**
     * 用户自助删除一个条目（记忆页每张卡片的「删除」）。
     *
     * <p>先把这一行读出来（读不到 = 本来就没有 或 不属于这个用户，两者都返回 0，不区分——
     * 区分开就等于提供一个探测别人记忆 id 是否存在的接口），再用它的 {@code (key, item_hash)}
     * 清墓碑，最后按主键置删除位。三步的顺序不能换：先删行再读 key 就拿不到 key 了。
     */
    @Override
    public int deleteByIdForUser(Long userId, Long id) {
        if (userId == null || id == null) {
            return 0;
        }
        ChatUserMemoryEntity row = memoryMapper.selectOne(new LambdaQueryWrapper<ChatUserMemoryEntity>()
                .eq(ChatUserMemoryEntity::getUserId, userId)
                .eq(ChatUserMemoryEntity::getId, id)
                .last("LIMIT 1"));
        if (row == null) {
            log.debug("待删除的记忆不存在或不属于该用户 userId={} id={}", userId, id);
            return 0;
        }
        int purged = row.getItemHash() == null
                ? 0
                : memoryMapper.purgeTombstoneOfItem(userId, row.getMemoryKey(), row.getItemHash());
        int deleted = memoryMapper.logicDeleteById(id, userId, null);
        log.info("用户自助删除单个长期记忆 userId={} id={} key={} 条数={} 清理墓碑={}",
                userId, id, row.getMemoryKey(), deleted, purged);
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
     * 写入一条记忆（<b>条目级</b>去重，V70）。
     *
     * <p>四道闸（键、长度、置信度、危险词）在<b>这里统一施加</b>——三条写入入口（工具、会话结束、
     * 归档补齐）都从这里过，调用方不需要自己重复判断，也就不可能漏掉某一道。
     *
     * <h2>去重粒度：同一句话，不是同一类</h2>
     * 身份由 {@code item_hash = SHA-256(规范化正文)} 决定：
     * <ul>
     *   <li>同一类、同一句话再写一次 → 命中已有行（刷新置信度/来源，不新增）；</li>
     *   <li>同一类、不同的话 → <b>新增一个条目</b>。这是 V70 的核心变化：以前「忌口」只有一个槽位，
     *       "不吃香菜"会被"海鲜过敏"覆盖掉。</li>
     * </ul>
     *
     * <h2>冲突策略</h2>
     * <ul>
     *   <li>{@link WriteSource#EXPLICIT}：用户当轮亲口说的算数；</li>
     *   <li>{@link WriteSource#BATCH}：同一句话的新置信度低于既有值时不刷新——否则一段老会话的
     *       迟到抽取会把用户今天刚说过的话的置信度拉低。</li>
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

        String itemHash = itemHashOf(normalizedText);
        ChatUserMemoryEntity existing = memoryMapper.selectOne(new LambdaQueryWrapper<ChatUserMemoryEntity>()
                .eq(ChatUserMemoryEntity::getUserId, userId)
                .eq(ChatUserMemoryEntity::getMemoryKey, normalizedKey)
                .eq(ChatUserMemoryEntity::getItemHash, itemHash)
                .last("LIMIT 1"));
        if (existing == null) {
            // 第五道闸（防复活）：用户刚删过<b>这一条</b>，自动抽取不许把它重新插回来。
            // 粒度是条目而不是类：删掉"不吃香菜"不该连累"海鲜过敏"在冷却期内都记不进来。
            if (source == WriteSource.BATCH && withinDeletedCooldown(userId, normalizedKey, itemHash)) {
                return SaveOutcome.rejected("该记忆刚被用户删除，冷却期内不自动重建");
            }
            ChatUserMemoryEntity row = new ChatUserMemoryEntity();
            row.setUserId(userId);
            row.setMemoryKey(normalizedKey);
            row.setMemoryText(normalizedText);
            row.setItemHash(itemHash);
            row.setConfidence(Math.min(score, 100));
            row.setSourceSessionId(sourceSessionId);
            memoryMapper.insert(row);
            log.info("新增长期记忆 userId={} key={} confidence={} 来源会话={} 来源={} 条目={}",
                    userId, normalizedKey, score, sourceSessionId, source, itemHash.substring(0, 8));
            return SaveOutcome.ok();
        }
        int existingConfidence = existing.getConfidence() == null ? 0 : existing.getConfidence();
        if (source == WriteSource.BATCH && score < existingConfidence) {
            log.info("跳过刷新长期记忆 userId={} key={}（批量抽取置信度 {} < 既有 {}）",
                    userId, normalizedKey, score, existingConfidence);
            return SaveOutcome.rejected("已有更可信的记忆（置信度 " + existingConfidence + "），本次不覆盖");
        }
        if (score <= existingConfidence) {
            // 同一句话、置信度没有提高：不写库。
            // 这里刻意<b>不</b>因为"来源会话变了"就刷新：来源应记"哪一次最确信地说过它"，
            // 而不是"最近一次提到它"；否则每轮闲聊都会刷新 gmt_modified，
            // 用户记忆页上的"最近更新"就变成噪声，冷却判断也会跟着漂移。
            log.debug("记忆条目未变化，跳过写入 userId={} key={} 条目={}",
                    userId, normalizedKey, itemHash.substring(0, 8));
            return SaveOutcome.ok();
        }
        ChatUserMemoryEntity update = new ChatUserMemoryEntity();
        update.setId(existing.getId());
        update.setConfidence(Math.min(score, 100));
        update.setSourceSessionId(sourceSessionId == null ? existing.getSourceSessionId() : sourceSessionId);
        memoryMapper.updateById(update);
        log.info("提升长期记忆置信度 userId={} key={} {} → {} 条目={} 来源={}",
                userId, normalizedKey, existingConfidence, update.getConfidence(),
                itemHash.substring(0, 8), source);
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

    /**
     * 这个条目是否处在"用户刚删过"的冷却期里（只用来阻止自动抽取把删掉的记忆重新插回来）。
     *
     * <p>为什么需要它：删除是逻辑删除，而会话结束时的抽取读的是<b>会话原文</b>——
     * 用户删掉"不吃香菜"之后，只要那段对话还在，下一轮抽取就会把这个事实重新读出来并 insert
     * 一条新的生效行（唯一键带 {@code is_deleted}，所以不撞键）。用户看到的就是"删了又回来"。
     *
     * <p>粒度是<b>条目</b>（key + item_hash）而不是类：V71 之后一类有多条，删掉"不吃香菜"
     * 不该让"海鲜过敏"在 30 天里都记不进来。
     *
     * <p>判断只看 {@code deleted_at}，因此不需要新表、不需要清理，冷却到期自动恢复。
     * 查询失败按"不在冷却期"处理：宁可让它复活（用户还能再删一次），也不要因为一次读失败把记忆饿死。
     * 日志里只打哈希前 8 位：记忆正文属于用户隐私，日志里出现原文等于把它写到了另一个介质上。
     */
    private boolean withinDeletedCooldown(Long userId, String key, String itemHash) {
        int days = aiProperties.getMemory().getDeletedKeyCooldownDays();
        if (days <= 0) {
            return false;
        }
        LocalDateTime lastDeletedAt;
        try {
            lastDeletedAt = memoryMapper.selectLastDeletedAt(userId, key, itemHash);
        } catch (RuntimeException e) {
            log.warn("查询记忆删除时间失败（按不在冷却期处理）userId={} key={}", userId, key, e);
            return false;
        }
        if (lastDeletedAt == null || lastDeletedAt.isBefore(LocalDateTime.now().minusDays(days))) {
            return false;
        }
        // 日志留痕是这套闸门可解释性的全部：用户问"为什么没记住"时，这行是唯一证据。
        log.info("跳过处于删除冷却期的长期记忆 userId={} key={} 条目={} 删除时间={} 冷却天数={}",
                userId, key, itemHash.substring(0, 8), lastDeletedAt, days);
        return true;
    }

    /**
     * 条目身份：规范化正文的 SHA-256（小写十六进制）。
     * <p>
     * 必须与 {@code V71__chat_user_memory_items.sql} 的回填口径一致（都是 SHA-256 十六进制小写），
     * 否则存量行在新代码眼里会变成"另一个条目"，用户会看到同一句话出现两次。
     * <p>
     * 之所以是 {@code public}：它是"这条记忆的身份"的唯一权威实现，运维脚本、数据修复、
     * 集成测试都需要用同一份口径计算，复制一份到别处必然漂移。
     */
    public static String itemHashOf(String normalizedText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizedText.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // JDK 强制要求支持 SHA-256，走到这里说明运行环境被裁剪过，属于部署故障而非输入问题
            throw new IllegalStateException("当前运行环境不支持 SHA-256，无法计算记忆条目标识", e);
        }
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
        // 关键词兜底默认关闭：有 tool calling 时由模型调 save_long_term_memory 决定记不记，
        // 关键词只会误判（"我记住了"、"记住这个数明天再看"）。开关放在这里而不是调用点，
        // 是因为它属于"这条抽取要不要做"的策略，和窗口宽度、冲突规则是同一层的事。
        if (REASON_KEYWORD_FALLBACK.equals(reason) && !aiProperties.getMemory().isKeywordTriggerFallback()) {
            log.debug("关键词兜底未开启，跳过抽取 sessionId={}", sessionId);
            return;
        }
        if (!concurrency.markExtractRunning(sessionId)) {
            log.debug("该会话已有记忆抽取任务在跑，跳过 sessionId={}", sessionId);
            return;
        }
        try {
            executor.execute(() -> {
                running.incrementAndGet();
                try {
                    runExtract(userId, sessionId, reason);
                } finally {
                    running.decrementAndGet();
                    concurrency.finishExtract(sessionId);
                }
            });
        } catch (RuntimeException e) {
            concurrency.finishExtract(sessionId);
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
            // 关键词兜底（没有 tool calling 的部署）只处理「用户刚刚说的那几句」：
            // 它由聊天主链路顺带触发、每轮都可能命中，窗口必须比终局复盘小得多，否则一次闲聊
            // 就烧掉两窗 × 40 条的模型输入。真正的整段复盘只发生在会话结束/归档。
            boolean fallback = REASON_KEYWORD_FALLBACK.equals(reason);
            int windowSize = fallback
                    ? Math.max(aiProperties.getMemory().getKeywordFallbackWindowMessages(), 1)
                    : Math.max(aiProperties.getMemory().getMemoryExtractWindowMessages(), 1);
            int maxWindows = fallback
                    ? 1
                    : Math.max(aiProperties.getMemory().getMemoryExtractMaxWindows(), 1);
            List<ChatMessage> recent = chatMessageMapper.selectRecentBySession(sessionId, windowSize * maxWindows);
            if (recent == null || recent.isEmpty()) {
                return 0;
            }
            int written = 0;
            int windows = 0;
            // 老窗先、新窗后：两个窗口提到同一个键时，后写的新说法才留得下来
            for (List<ChatMessage> window : splitOldestFirst(recent, windowSize)) {
                windows++;
                List<MemoryCandidate> candidates = extractCandidates(buildInput(userId, window), userId, sessionId);
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
    private List<MemoryCandidate> extractCandidates(String input, Long userId, Long sessionId) {
        // 账单归属：抽取是每条会话终局/显式触发的一次额外调用，成本要能单独归集
        AiCallContext.Call callContext = new AiCallContext.Call(SceneCode.MEMORY, userId, sessionId);
        var prompt = clients.utilityClient().prompt()
                .system(promptTemplateService.systemPrompt(SceneCode.MEMORY))
                .options(sceneModelResolver.memoryOptionsBuilder())
                .user(input);
        if (!aiProperties.getMemory().isStructuredOutput()) {
            return parse(AiCallContext.run(callContext, () -> prompt.call().content()));
        }
        MemoryExtractionDtos.MemoryPayload payload = AiCallContext.run(callContext, () -> prompt
                .advisors(a -> a.advisors(STRUCTURED_OUTPUT_ADVISOR))
                .call()
                .entity(MemoryExtractionDtos.MemoryPayload.class));
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
        return running.get() > 0;
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
