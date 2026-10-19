package com.threadfit.glucopilot.ai.memory;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * C 端"系统记住了我什么"（③ 期合规自助）：查询与删除自己的跨会话长期记忆。
 *
 * <h2>为什么字段比管理端少一半</h2>
 * 管理端 4.14 的抽屉要带上 {@code id} / {@code sourceSessionId} / {@code isAuto}，
 * 那是给运营做"这条记忆是哪次对话抽出来的、要不要人工干预"的判断用的。
 * 用户自己不需要这些内部标识，也<b>不应该</b>看到：
 * <ul>
 *   <li>{@code id} 是内部主键，露出去只会诱导前端拿它做单条删除，而删除的正确粒度是"全部"；</li>
 *   <li>{@code sourceSessionId} 指向一段可能已被用户删除的对话——把它的存在告诉用户，
 *       等于变相说明"那段对话还在系统里留有痕迹"；</li>
 *   <li>{@code isAuto} 是运营侧的启发式判据（见 {@code AdminChatMemoryDtos}），
 *       对用户没有意义，反而容易被误读成"这条是假的"。</li>
 * </ul>
 * 只给"记了什么（key + 正文）、有多确定、什么时候更新过"，就足以让用户判断要不要让系统忘掉。
 *
 * <h2>为什么保留 {@code memoryKey} 而不只给正文</h2>
 * 同一条键会被新信息覆盖（见 {@code UserChatMemoryService#upsert}），前端按 key 分组展示
 * 才能表达"这是系统对你的忌口认定的当前值"，而一堆互不相干的句子读起来像聊天记录。
 */
public final class UserChatMemoryDtos {

    private UserChatMemoryDtos() {
    }

    /** 一条属于当前用户的长期记忆。 */
    @Data
    @Builder
    public static class MemoryRow {

        /** 记忆键，如 {@code diet_taboo}（取值受写入侧白名单约束，见 UserChatMemoryService）。 */
        private String memoryKey;

        /** 记忆正文。 */
        private String memoryText;

        /** 置信度 0~100：低于 60 的本就不会写进来，这里给出来是让用户知道"系统有多确定"。 */
        private Integer confidence;

        /** 最近更新时间（取 gmt_modified：覆盖写会刷新它）。 */
        private LocalDateTime updatedAt;
    }

    /**
     * 记忆列表。
     * <p>
     * 不分页：写入侧只允许五类键（{@code ALLOWED_KEYS}），且同键至多一条有效记忆，
     * 因此这里的行数是<b>有界</b>的。合规页面的语义是"我到底被记了什么"，
     * 分页只会让人以为"还有没看到的"，反而损害透明性。
     */
    @Data
    @Builder
    public static class MemoryList {

        private List<MemoryRow> records;
    }

    /** 删除结果：真实删除条数（0 = 本来就没有记忆，同样算成功）。 */
    @Data
    @Builder
    public static class DeleteResult {

        private int deleted;
    }
}
