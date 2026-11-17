package com.threadfit.glucopilot.ai.memory.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * C 端"系统记住了我什么"（③ 期合规自助）：查询与删除自己的跨会话长期记忆。
 *
 * <h2>为什么现在给 id，而之前只给 key</h2>
 * V71 之前是「一类一条」的槽位模型，{@code (user_id, memory_key)} 就是用户眼里的"这一条"，
 * 所以单条删除按键做是合理的，暴露主键只会多一个越权入口。
 * 改成「分类 + 多条目」之后，同一条键下可以有多条（不吃香菜 / 不吃辣 / 海鲜过敏），
 * <b>按类删除会连带删掉另外两条</b>——用户点"删掉香菜"却把"海鲜过敏"也删了，是不可接受的结果。
 * 于是主键必须给出来：{@code DELETE /items/{id}} 的 id 由服务端在查询时按当前用户过滤，删除时
 * 仍然只允许删 {@code user_id = 当前用户} 的行（见 {@code ChatUserMemoryMapper#logicDeleteById}）。
 *
 * <h2>为什么字段比管理端少</h2>
 * 管理端 4.14 的抽屉要带 {@code sourceSessionId} / {@code isAuto}，那是给运营判断
 * "这条记忆是哪次对话抽出来的、要不要人工干预"用的。用户自己不需要，也<b>不应该</b>看到：
 * <ul>
 *   <li>{@code sourceSessionId} 指向一段可能已被用户删除的对话——把它的存在告诉用户，
 *       等于变相说明"那段对话还在系统里留有痕迹"；</li>
 *   <li>{@code isAuto} 是运营侧的启发式判据（见 {@code AdminChatMemoryDtos}），
 *       对用户没有意义，反而容易被误读成"这条是假的"。</li>
 * </ul>
 * 只给"记了什么（key + 正文）、有多确定、什么时候更新过"，足以让用户判断要不要让系统忘掉。
 */
public final class UserChatMemoryDtos {

    private UserChatMemoryDtos() {
    }

    /** 一条属于当前用户的长期记忆。 */
    @Data
    @Builder
    public static class MemoryRow {

        /**
         * 条目主键。
         * <p>
         * 只用于单条删除（{@code DELETE /items/{id}}）与前端列表的 {@code wx:key}；
         * 服务端删除时仍会带上 {@code user_id} 过滤，拿到别人的 id 也删不掉。
         */
        private Long id;

        /** 记忆键，如 {@code diet_taboo}（取值受写入侧白名单约束，见 UserChatMemoryService）。 */
        private String memoryKey;

        /** 记忆正文。 */
        private String memoryText;

        /** 置信度 0~100：低于 60 的本就不会写进来，这里给出来是让用户知道"系统有多确定"。 */
        private Integer confidence;

        /** 最近更新时间（取 gmt_modified：覆盖写会刷新它）。 */
        private LocalDateTime updatedAt;
    }

    /** 删除结果：真实删除条数（0 = 本来就没有这条/这一类的记忆，同样算成功）。 */
    @Data
    @Builder
    public static class DeleteResult {

        private int deleted;
    }

    /**
     * 记忆页的一次加载结果：当前页 + 全局的每类条数。
     *
     * <h2>为什么不直接用通用 {@code PageResult}</h2>
     * 记忆页顶部是五个分类入口，它们要显示「各几条」——这个数字必须与当前页翻到第几页、
     * 当前在筛哪一类<b>无关</b>。若只给分页对象，页面要么为统计再开一个接口（每次加载多一次往返），
     * 要么把"总数"当成分类型的数字（筛过之后就不对了）。这个视图对象把两者合成一次响应。
     *
     * <h2>counts 的形状</h2>
     * 键是 memoryKey，值是该类生效条数；<b>没有记忆的类也会出现（值 0）</b>，
     * 前端渲染入口时就不必区分"这一类为零"与"这一类没出现在结果里"——
     * 否则用户会看到入口数量忽隐忽现。后端新增的未知类同样会出现在这里，
     * 前端可以据此保留它，而不是静默丢掉。
     */
    @Data
    @Builder
    public static class MemoryPage {

        /** 当前页的条目。 */
        private List<MemoryRow> records;

        /** 符合当前筛选条件的总条数（不带筛选时为全部条数）。 */
        private long total;

        /** 总页数（按当前 pageSize 计算）。 */
        private long pages;

        private long pageNum;

        private long pageSize;

        /** 每类生效条数，不受当前筛选影响。 */
        private Map<String, Integer> counts;
    }
}
