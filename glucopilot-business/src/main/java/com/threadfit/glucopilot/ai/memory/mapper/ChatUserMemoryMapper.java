package com.threadfit.glucopilot.ai.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.threadfit.glucopilot.ai.memory.entity.ChatUserMemoryEntity;

import java.time.LocalDateTime;

/**
 * 长期记忆读写（chat_user_memory）。
 *
 * <h2>为什么带 user_id 的删除写成自写 SQL，而不是循环 updateById</h2>
 * <ul>
 *   <li><b>一条语句 vs N 条</b>：合规删除面对的是"这个用户的全部记忆"，
 *       逐条逻辑删除既慢又会把删除过程拉长成多事务；一条 UPDATE 原子完成；</li>
 *   <li><b>归属写进 WHERE</b>：越权删除不能靠"先查再删"两步（并发下来单会漏），
 *       把 user_id 直接写进条件，删不到别人的记忆；</li>
 *   <li><b>三个列必须同一条语句里一起改</b>：DDL 的 {@code chk_cum_del_consistent} 要求
 *       {@code is_deleted = 1} 时 {@code deleted_at} 非空。MyBatis-Plus 的逻辑删除只会置
 *       {@code is_deleted}，缺了 deleted_at 会被 CHECK 拒绝——这类失败在测试环境很难复现，
 *       所以这里显式写全。</li>
 * </ul>
 * 逻辑删除后同 key 可以再写新记忆：唯一键是 {@code uk_user_key_active(user_id, memory_key, is_deleted)}，
 * 生效行（0）与已删行（1）互不冲突。<b>刻意不做物理删除</b>——"系统曾经记过什么"本身就是要留痕的事实。
 */
public interface ChatUserMemoryMapper extends BaseMapper<ChatUserMemoryEntity> {

    /**
     * 逻辑删除某用户的全部长期记忆（管理端 4.15）。
     *
     * @param userId    用户 ID（写进 WHERE，防止越权删到别人）
     * @param deletedBy 操作人（运营账号 ID）；列允许为空，
     *                  非运营线程（如用户自助清除）拿不到 opsId 时按 null 落库
     * @return 实际逻辑删除的条数；0 表示该用户本来就没有记忆（幂等，不报错）
     */
    @Update("UPDATE chat_user_memory SET is_deleted = 1, deleted_at = NOW(3), deleted_by = #{deletedBy} "
            + "WHERE user_id = #{userId} AND is_deleted = 0")
    int logicDeleteAllByUser(@Param("userId") Long userId, @Param("deletedBy") Long deletedBy);

    /**
     * 逻辑删除某个键的长期记忆（C 端记忆页「删除这一条」）。
     *
     * <p>为什么按 {@code memory_key} 而不是按 {@code id}：用户面对的是"系统记下的这条忌口"，
     * 不是数据库里的某一行；键受 {@code ALLOWED_KEYS} 白名单约束、同一时间至多一条生效行，
     * 所以 {@code (user_id, memory_key)} 就是用户眼里的"这一条"。用 id 反而要求 DTO 把内部主键
     * 暴露给客户端——那是越权删别人记忆的入口。
     *
     * <p>与 {@link #logicDeleteAllByUser} 同样三个列一起改：DDL 的 {@code chk_cum_del_consistent}
     * 要求删除态必须同时写 {@code deleted_at}，只置 {@code is_deleted} 会被 MySQL 拒绝。
     *
     * @param deletedBy 恒为 {@code null}：用户行使删除权，不是运营代删（运营留痕走 ops_audit_log）
     * @return 1 = 删掉了；0 = 本来就没有这条（幂等，不算错误）
     */
    @Update("UPDATE chat_user_memory SET is_deleted = 1, deleted_at = NOW(3), deleted_by = #{deletedBy} "
            + "WHERE user_id = #{userId} AND memory_key = #{memoryKey} AND is_deleted = 0")
    int logicDeleteByKey(@Param("userId") Long userId,
                         @Param("memoryKey") String memoryKey,
                         @Param("deletedBy") Long deletedBy);

    /**
     * 该用户这个键<b>最近一次被删除</b>的时间（没删过则为 null）。
     *
     * <p>用途只有一个：写入侧的"防复活"闸门——用户删掉一条记忆后，会话结束时的自动抽取
     * 会从会话原文里把同一个事实再读出来、再插回去，用户看到的就是"删除没生效"。
     * 判断依据只能是 {@code deleted_at}（DDL 保证删除态一定有值），所以这里<b>刻意绕开</b>
     * {@code @TableLogic}：要查的正是被它隐藏掉的已删行。
     *
     * <p>走 {@code uk_user_key_active(user_id, memory_key, is_deleted)} 的键前缀，成本可忽略。
     */
    @Select("SELECT MAX(deleted_at) FROM chat_user_memory "
            + "WHERE user_id = #{userId} AND memory_key = #{memoryKey} AND is_deleted = 1")
    LocalDateTime selectLastDeletedAt(@Param("userId") Long userId, @Param("memoryKey") String memoryKey);

    /**
     * 账号注销：物理删除该用户的全部长期记忆。
     * <p>
     * 与上面的逻辑删除刻意不同——注销后"系统记过什么"不能以任何形式留存，
     * 逻辑删除的行仍然可读，不满足删除权。
     */
    @Delete("DELETE FROM chat_user_memory WHERE user_id = #{userId}")
    int purgeByUserId(@Param("userId") Long userId);

    /**
     * 审计分页（管理端「AI 审计 → 记忆」）。
     *
     * <p>{@code includeDeleted} 的取舍：默认只看生效行，因为运营日常要回答的是
     * 「系统现在记着什么」；但审计要能回答「曾经记过什么、什么时候被抹掉的」，
     * 所以给了显式开关，而不是干脆把已删行滤掉——滤掉就等于失去了核查删除行为的能力。
     *
     * <p>排序用 {@code gmt_modified DESC}：记忆是「最新覆盖最旧」的更新型数据，
     * 按创建时间排会把刚被改写的老记忆压到后面。
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
