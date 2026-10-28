package com.threadfit.glucopilot.ai.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import com.threadfit.glucopilot.ai.memory.entity.ChatUserMemoryEntity;

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
     * 账号注销：物理删除该用户的全部长期记忆。
     * <p>
     * 与上面的逻辑删除刻意不同——注销后"系统记过什么"不能以任何形式留存，
     * 逻辑删除的行仍然可读，不满足删除权。
     */
    @Delete("DELETE FROM chat_user_memory WHERE user_id = #{userId}")
    int purgeByUserId(@Param("userId") Long userId);
}
