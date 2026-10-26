package com.threadfit.glucopilot.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.user.entity.UserAccountCancellation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserAccountCancellationMapper extends BaseMapper<UserAccountCancellation> {

    /**
     * 抢占执行权：PENDING(0) → RUNNING(1)。
     * <p>
     * 用一条带 {@code status = 0} 条件的 UPDATE 而不是"先查再改"：多实例轮询时，
     * 只有一个实例能把行从 0 改成 1，另一个拿到 0 行受影响就跳过——这是唯一的并发闸门。
     */
    @Update("UPDATE user_account_cancellation SET status = 1 WHERE id = #{id} AND status = 0")
    int claim(@Param("id") Long id);

    /** 清理完成：RUNNING(1) → EXECUTED(2)，同时清零失败计数。 */
    @Update("UPDATE user_account_cancellation SET status = 2, executed_at = NOW(3), "
            + "fail_count = 0, last_error = NULL WHERE id = #{id} AND status = 1")
    int markExecuted(@Param("id") Long id);

    /**
     * 本次清理失败：失败计数 +1，并按调用方给的下一状态回退。
     * <p>
     * {@code nextStatus} 是 0（可重试）或 4（超过重试上限）。<b>绝不回退为 NORMAL</b>：
     * 一旦开始物理删除，"半删干净但仍可用"的账号比停在注销中更危险。
     */
    @Update("UPDATE user_account_cancellation SET status = #{nextStatus}, "
            + "fail_count = fail_count + 1, last_error = #{lastError} WHERE id = #{id} AND status = 1")
    int markFailed(@Param("id") Long id, @Param("nextStatus") int nextStatus,
                   @Param("lastError") String lastError);

    /**
     * 恢复：PENDING(0) → RECOVERED(3)。
     * <p>
     * CAS 条件同样重要：worker 可能刚把行抢成 RUNNING(1)，此时恢复必须失败，
     * 否则会出现"用户以为恢复了、数据其实正在被删"的错位。
     */
    @Update("UPDATE user_account_cancellation SET status = 3, recovered_at = NOW(3) "
            + "WHERE id = #{id} AND status = 0")
    int markRecovered(@Param("id") Long id);
}