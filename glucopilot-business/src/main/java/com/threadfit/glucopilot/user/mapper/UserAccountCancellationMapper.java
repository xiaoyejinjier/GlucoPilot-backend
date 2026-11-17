package com.threadfit.glucopilot.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.user.entity.UserAccountCancellation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 注销凭据的终态条件更新。
 *
 * <p>没有"抢占"这一步：worker 处理时记录始终保持 PENDING，靠"时间窗隔离 + 幂等重试"保证安全。
 * 这里的三条 UPDATE 都是<b>前置态条件更新</b>，作用是防止状态被其它路径改写后重复落子。
 */
@Mapper
public interface UserAccountCancellationMapper extends BaseMapper<UserAccountCancellation> {

    /**
     * 清理完成：待执行/历史执行中 → EXECUTED(2)，同时清零失败计数。
     *
     * <p>前态含 {@code status = 1} 只为兼容历史版本写入的 RUNNING 僵尸行。
     */
    @Update("UPDATE user_account_cancellation SET status = 2, executed_at = #{now}, "
            + "fail_count = 0, last_error = NULL WHERE id = #{id} AND status IN (0, 1)")
    int markExecuted(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 本次清理失败：失败计数 +1，并按调用方给的下一状态回退。
     * <p>
     * {@code nextStatus} 是 0（可重试）或 4（超过重试上限）。<b>绝不回退为 NORMAL</b>：
     * 一旦开始物理删除，"半删干净但仍可用"的账号比停在注销中更危险。
     */
    @Update("UPDATE user_account_cancellation SET status = #{nextStatus}, "
            + "fail_count = fail_count + 1, last_error = #{lastError} "
            + "WHERE id = #{id} AND status IN (0, 1)")
    int markFailed(@Param("id") Long id, @Param("nextStatus") int nextStatus,
                   @Param("lastError") String lastError);

    /**
     * 恢复：PENDING(0) → RECOVERED(3)。
     *
     * <p>这是"登录恢复 vs 到期清理"在窗口边界的唯一裁判：worker 已把行移出 PENDING 时恢复必须失败。
     * 时间戳由应用写入，与 {@code rejectIfRecentlyRecovered} 的比较口径保持一致。
     */
    @Update("UPDATE user_account_cancellation SET status = 3, recovered_at = #{now} "
            + "WHERE id = #{id} AND status = 0")
    int markRecovered(@Param("id") Long id, @Param("now") LocalDateTime now);
}
