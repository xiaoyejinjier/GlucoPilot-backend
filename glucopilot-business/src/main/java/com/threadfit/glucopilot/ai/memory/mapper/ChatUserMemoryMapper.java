package com.threadfit.glucopilot.ai.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.threadfit.glucopilot.ai.memory.entity.ChatUserMemoryEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 长期记忆读写（chat_user_memory）。
 *
 * <h2>条目的身份是「内容」，不是「类」</h2>
 * 唯一键 {@code uk_user_key_item(user_id, memory_key, item_hash, is_deleted)}：
 * 同一个人的同一类里，<b>同一句话</b>只有一条生效行，不同内容各占一行。
 * 于是「忌口」能同时记着不吃香菜、不吃辣、海鲜过敏（V71 之前是三类事实抢一个槽位）。
 *
 * <h2>为什么删除要先清墓碑</h2>
 * 唯一键把 {@code is_deleted} 编了进去（0 = 生效、1 = 已删），而它只有两个取值——
 * 这意味着每个 {@code (user, key, item_hash)} <b>最多只能存一条已删行</b>。
 * 但用户的真实动线是「删掉香菜 → 又说了一次不能吃香菜 → 再删一次」：
 * 第二次删除会把 {@code is_deleted} 从 0 改成 1，与第一条墓碑撞键（MySQL 1062）。
 * 因此所有删除入口都先把同范围的历史墓碑物理删掉，再置删除位：
 * <ul>
 *   <li>墓碑的唯一用途是写入侧的「防复活」冷却，而冷却只取 {@code MAX(deleted_at)}，
 *       留更早的墓碑没有任何功能价值；</li>
 *   <li>真正需要留痕的是运营侧审计与用户侧的"我删过什么"，前者走 {@code ops_audit_log}，
 *       后者不需要历史版本（用户看到"已删除"就够了）。</li>
 * </ul>
 *
 * <h2>为什么带 user_id 的删除写成自写 SQL，而不是循环 updateById</h2>
 * 一条 UPDATE 原子完成全部删除；把 {@code user_id} 写进 WHERE，删不到别人的记忆；
 * 三个列同一条语句里一起改，满足 {@code chk_cum_del_consistent}
 * （{@code is_deleted = 1} 时 {@code deleted_at} 必须非空，而 MyBatis-Plus 的逻辑删除只置标记位）。
 */
public interface ChatUserMemoryMapper extends BaseMapper<ChatUserMemoryEntity> {

    /**
     * 逻辑删除某用户的全部长期记忆（C 端「清空全部」、管理端 4.15）。
     *
     * @param userId    用户 ID（写进 WHERE，防止越权）
     * @param deletedBy 操作人（运营账号 ID）；用户自助删除时为 null
     * @return 实际逻辑删除的条数；0 表示本来就没有生效记忆（幂等，不报错）
     */
    @Update("UPDATE chat_user_memory SET is_deleted = 1, deleted_at = NOW(3), deleted_by = #{deletedBy} "
            + "WHERE user_id = #{userId} AND is_deleted = 0")
    int logicDeleteAllByUser(@Param("userId") Long userId, @Param("deletedBy") Long deletedBy);

    /**
     * 逻辑删除某一类下的全部条目（C 端记忆页「清空本类」）。
     *
     * @return 实际删除条数；0 = 这一类本来就没有生效条目
     */
    @Update("UPDATE chat_user_memory SET is_deleted = 1, deleted_at = NOW(3), deleted_by = #{deletedBy} "
            + "WHERE user_id = #{userId} AND memory_key = #{memoryKey} AND is_deleted = 0")
    int logicDeleteByKey(@Param("userId") Long userId,
                         @Param("memoryKey") String memoryKey,
                         @Param("deletedBy") Long deletedBy);

    /**
     * 逻辑删除一个条目（C 端记忆页「删除这一条」）。
     *
     * <p>{@code user_id} 与 {@code id} 同时进 WHERE：客户端拿到的 id 是它自己列表里的，
     * 但没有理由相信它不会改成别人的 id 再发一次。
     */
    @Update("UPDATE chat_user_memory SET is_deleted = 1, deleted_at = NOW(3), deleted_by = #{deletedBy} "
            + "WHERE id = #{id} AND user_id = #{userId} AND is_deleted = 0")
    int logicDeleteById(@Param("id") Long id,
                        @Param("userId") Long userId,
                        @Param("deletedBy") Long deletedBy);

    /** 物理清掉某个条目的历史墓碑（删除前调用，避免二次删除撞唯一键）。 */
    @Delete("DELETE FROM chat_user_memory "
            + "WHERE user_id = #{userId} AND memory_key = #{memoryKey} AND item_hash = #{itemHash} AND is_deleted = 1")
    int purgeTombstoneOfItem(@Param("userId") Long userId,
                             @Param("memoryKey") String memoryKey,
                             @Param("itemHash") String itemHash);

    /** 物理清掉某一类的历史墓碑（清空本类前调用）。 */
    @Delete("DELETE FROM chat_user_memory WHERE user_id = #{userId} AND memory_key = #{memoryKey} AND is_deleted = 1")
    int purgeTombstonesByKey(@Param("userId") Long userId, @Param("memoryKey") String memoryKey);

    /** 物理清掉该用户的全部历史墓碑（清空全部前调用）。 */
    @Delete("DELETE FROM chat_user_memory WHERE user_id = #{userId} AND is_deleted = 1")
    int purgeTombstonesByUser(@Param("userId") Long userId);

    /**
     * 该用户这个条目<b>最近一次被删除</b>的时间（没删过则为 null）。
     *
     * <p>用途只有一个：写入侧的「防复活」闸门——用户删掉一条记忆后，会话结束时的自动抽取
     * 会从会话原文里把同一句话再读出来、再插回去，用户看到的就是"删除没生效"。
     * 判断粒度是<b>条目</b>（key + item_hash）：删掉"不吃香菜"不该连累"海鲜过敏"被一起冻结。
     *
     * <p>刻意绕开 {@code @TableLogic}：要查的正是被它隐藏掉的已删行。
     */
    @Select("SELECT MAX(deleted_at) FROM chat_user_memory "
            + "WHERE user_id = #{userId} AND memory_key = #{memoryKey} AND item_hash = #{itemHash} "
            + "AND is_deleted = 1")
    LocalDateTime selectLastDeletedAt(@Param("userId") Long userId,
                                      @Param("memoryKey") String memoryKey,
                                      @Param("itemHash") String itemHash);

    /**
     * 注入用的候选集：生效条目按「置信度降序、最近更新降序」取前 N 条。
     *
     * <p>为什么在 SQL 里 LIMIT 而不是全量取出再在内存里裁：条目数不设上限，
     * 重度用户可能有几百条，全量拉取既慢又没必要——注入侧只关心最靠前的那批。
     */
    @Select("SELECT * FROM chat_user_memory WHERE user_id = #{userId} AND is_deleted = 0 "
            + "ORDER BY confidence DESC, gmt_modified DESC, id DESC LIMIT #{limit}")
    List<ChatUserMemoryEntity> selectActiveByUser(@Param("userId") Long userId, @Param("limit") int limit);

    /**
     * 注入用的候选集：<b>每一类各取前 {@code perKey} 条</b>（类内按置信度降序、最近更新降序）。
     *
     * <p>为什么不能再用 {@link #selectActiveByUser} 的「全局 top-N + 内存分桶」：
     * V71 之后同一类可以有任意多条，而全局 top-N 是按置信度排的——某个用户攒了 24 条高置信度的忌口，
     * 就会把「控糖目标」整类挤出候选窗口，模型下一轮会反过来问用户"你的目标是什么"。
     * 这是一个结构性问题（条目越多越容易发生），分桶解决不了，只能让每一类各自排序、各自限量。
     *
     * <p>用窗口函数而不是「查一次所有类再循环查五次」：一条 SQL、一次往返，且不会出现
     * 「查第二类时数据已经被改」的读不一致。
     */
    @Select("SELECT * FROM (SELECT m.*, "
            + "ROW_NUMBER() OVER (PARTITION BY m.memory_key "
            + "ORDER BY m.confidence DESC, m.gmt_modified DESC, m.id DESC) AS rn "
            + "FROM chat_user_memory m WHERE m.user_id = #{userId} AND m.is_deleted = 0) t "
            + "WHERE t.rn <= #{perKey} "
            + "ORDER BY t.confidence DESC, t.gmt_modified DESC, t.id DESC")
    List<ChatUserMemoryEntity> selectTopPerKey(@Param("userId") Long userId, @Param("perKey") int perKey);

    /** 该用户当前生效条目数（只用于"抽取是否失控"的告警，不做硬限制）。 */
    @Select("SELECT COUNT(*) FROM chat_user_memory WHERE user_id = #{userId} AND is_deleted = 0")
    int countActiveByUser(@Param("userId") Long userId);

    /**
     * 某一类下还有多少条生效条目。
     *
     * <p>删除入口用它做前置判断：<b>没有生效行就不要清墓碑</b>。
     * 否则"重复点删除"会把上一次删除留下的墓碑一并物理删掉——那条墓碑正是审计与冷却要用的证据，
     * 幂等的删除请求不该产生这种副作用。
     */
    @Select("SELECT COUNT(*) FROM chat_user_memory "
            + "WHERE user_id = #{userId} AND memory_key = #{memoryKey} AND is_deleted = 0")
    int countActiveByKey(@Param("userId") Long userId, @Param("memoryKey") String memoryKey);

    /**
     * 按类统计当前用户的生效条数（记忆页顶部「几类、各几条」的数据来源）。
     *
     * <p>为什么一条 {@code GROUP BY} 而不是循环调 {@link #countActiveByKey}：
     * 记忆页每次加载都要带上这份统计，五类就是五次往返；一条聚合查询把它压成一次。
     * 这里也<b>刻意不</b>接受筛选条件——入口上的数字要告诉用户"这一类里一共有几条"，
     * 若跟着筛选走，点进去之后就再也看不到别类的条数了。
     *
     * <p>别名写成驼峰，由 {@link ChatUserMemoryKeyCount} 承接：MySQL 原样返回列名、
     * H2 会转大写，直接按 Map 取键会在单测与生产之间错位。
     */
    @Select("SELECT memory_key AS memoryKey, COUNT(*) AS total FROM chat_user_memory "
            + "WHERE user_id = #{userId} AND is_deleted = 0 GROUP BY memory_key")
    List<ChatUserMemoryKeyCount> countActiveGroupByKey(@Param("userId") Long userId);

    /**
     * 读工具用的候选集：可按类与关键词收窄，排序与注入口径一致（置信度降序、最近更新降序）。
     *
     * <p>为什么排序必须与 {@code forInjection} 一致：模型从两条路径看到同一批记忆，
     * 若一边"最可信的在前"、另一边"最新的在前"，它会对"哪条更重要"得出互相矛盾的判断。
     *
     * <p>为什么筛选条件用两段 {@code <if>} 而不是在 Java 里过滤：条目数不设上限，
     * 先全量拉回来再筛会把"记忆多的用户"变成慢查询；收窄条件放在 SQL 里才能只取需要的行。
     * 关键词用 {@code LOCATE} 而非 {@code LIKE}：不需要转义 {@code %} 与 {@code _}，
     * 用户的忌口词里出现这些字符时不会变成通配符。
     */
    @Select("<script>SELECT * FROM chat_user_memory WHERE user_id = #{userId} AND is_deleted = 0"
            + "<if test='memoryKey != null'> AND memory_key = #{memoryKey}</if>"
            + "<if test='keyword != null'> AND LOCATE(#{keyword}, memory_text) &gt; 0</if>"
            + " ORDER BY confidence DESC, gmt_modified DESC, id DESC LIMIT #{limit}</script>")
    List<ChatUserMemoryEntity> selectActiveFiltered(@Param("userId") Long userId,
                                                    @Param("memoryKey") String memoryKey,
                                                    @Param("keyword") String keyword,
                                                    @Param("limit") int limit);

    /**
     * 与 {@link #selectActiveFiltered} 同条件的计数。
     *
     * <p>为什么单独查一次而不是用「查回来的行数」：读工具要把「一共有几条、你看到几条」同时告诉模型，
     * 否则它会把"我看到了 15 条"当成"总共 15 条"——这正是注入侧一直存在的毛病。
     */
    @Select("<script>SELECT COUNT(*) FROM chat_user_memory WHERE user_id = #{userId} AND is_deleted = 0"
            + "<if test='memoryKey != null'> AND memory_key = #{memoryKey}</if>"
            + "<if test='keyword != null'> AND LOCATE(#{keyword}, memory_text) &gt; 0</if></script>")
    int countActiveFiltered(@Param("userId") Long userId,
                            @Param("memoryKey") String memoryKey,
                            @Param("keyword") String keyword);

    /**
     * 账号注销：物理删除该用户的全部长期记忆（含墓碑）。
     * <p>
     * 与逻辑删除刻意不同——注销后"系统记过什么"不能以任何形式留存。
     */
    @Delete("DELETE FROM chat_user_memory WHERE user_id = #{userId}")
    int purgeByUserId(@Param("userId") Long userId);

    /**
     * 审计分页（管理端「AI 审计 → 记忆」）。
     *
     * <p>{@code includeDeleted} 的取舍：默认只看生效行，因为运营日常要回答的是
     * 「系统现在记着什么」；但审计要能回答「曾经记过什么、什么时候被抹掉的」，
     * 所以给了显式开关。
     *
     * <p>排序用 {@code gmt_modified DESC}：记忆是「最近更新最有价值」的数据。
     */
    @Select("<script>"
            + "SELECT * FROM chat_user_memory WHERE 1 = 1"
            + "<if test='!includeDeleted'> AND is_deleted = 0</if>"
            + "<if test='userId != null'> AND user_id = #{userId}</if>"
            + "<if test='memoryKey != null'> AND memory_key = #{memoryKey}</if>"
            + "<if test='keyword != null'> AND LOCATE(#{keyword}, memory_text) &gt; 0</if>"
            + " ORDER BY gmt_modified DESC, id DESC"
            + "</script>")
    IPage<ChatUserMemoryEntity> selectAuditPage(IPage<ChatUserMemoryEntity> page,
                                                @Param("userId") Long userId,
                                                @Param("memoryKey") String memoryKey,
                                                @Param("keyword") String keyword,
                                                @Param("includeDeleted") boolean includeDeleted);
}
