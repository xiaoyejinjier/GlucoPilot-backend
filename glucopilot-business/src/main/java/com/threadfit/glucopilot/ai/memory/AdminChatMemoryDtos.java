package com.threadfit.glucopilot.ai.memory;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端"用户 AI 长期记忆"（契约 4.14 查询 / 4.15 删除）的出参。
 *
 * <h2>为什么长期记忆值得单开一个运营只读页</h2>
 * 记忆与对话内容不是一回事：对话是"用户说过的话"，记忆是"系统据此认定了什么"，而且它<b>跨会话生效</b>——
 * 用户在小程序里只能看到本会话，看不到系统对他的画像，更看不到一条记错的偏好如何在之后每一轮回答里被引用。
 * 因此合规上必须能回答两个问题：系统记住了这个用户什么（4.14）、能不能让系统彻底忘掉（4.15）。
 *
 * <h2>为什么这里反而要返回 memory_text 原文</h2>
 * 与审计日志的纪律相反（见 {@link AdminChatMemoryService} 的类注释）：这两个接口本身就是"看记忆/删记忆"，
 * 脱敏之后既看不出记错了什么，也审不出该删哪条。风险靠权限点隔离（{@code ai:memory:view} /
 * {@code ai:memory:manage}）与"每次查询都落审计"来控制，而不是靠把内容藏起来——
 * 藏起来只会让运营去要一个更宽的权限，风险更大。
 */
public final class AdminChatMemoryDtos {

    private AdminChatMemoryDtos() {
    }

    /** 一条长期记忆。 */
    @Data
    @Builder
    public static class MemoryRow {

        /** 记忆 ID（Long 由全局 Jackson 配置序列化为字符串，防前端精度丢失）。 */
        private Long id;

        /** 记忆键，如 {@code diet_taboo}；前端按运营侧的键名映射表展示中文。 */
        private String memoryKey;

        /** 记忆正文。 */
        private String memoryText;

        /** 置信度 0~100。 */
        private Integer confidence;

        /** 来源会话 ID，可空。 */
        private Long sourceSessionId;

        /** 最近更新时间（取 gmt_modified：覆盖写会刷新它，比创建时间更能反映"当前这条记忆"）。 */
        private LocalDateTime updatedAt;

        /**
         * 是否由自动抽取写入。
         * <p>
         * <b>判据是启发式，只用于展示</b>：{@code created_by} 为 0/null 视为自动抽取（系统写入时没有
         * 具体操作人），否则视为人工/用户自助写入。<b>它不是权限判据</b>——是否需要更高权限取决于
         * 操作者是谁，与这条记忆当初怎么来的无关；把它当权限用会导致"自动抽取的记忆谁都能删"这类漏洞。
         */
        private Boolean isAuto;
    }

    /**
     * 记忆列表。
     * <p>
     * 只带 {@code records + total}（契约 4.14 明确写在 {@code data} 内），不额外回 pageNum/pageSize：
     * 运营页是"某用户详情抽屉"里的短列表，多两个回显字段只会让前端多一处可能与请求参数不一致的状态。
     */
    @Data
    @Builder
    public static class MemoryList {

        private List<MemoryRow> records;

        /** 该用户的有效（未逻辑删除）记忆总数，用于前端判断"还有下一页"。 */
        private Long total;
    }

    /** 删除结果：返回真实删除条数，前端据此区分"删掉了 N 条"与"本来就没有记忆"。 */
    @Data
    @Builder
    public static class DeleteResult {

        private int deleted;
    }
}
