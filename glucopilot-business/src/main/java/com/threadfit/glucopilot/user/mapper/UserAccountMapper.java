package com.threadfit.glucopilot.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.user.entity.UserAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {

    /**
     * 账号注销：写墓碑（PII 置空 + 终态 3）。
     * <p>
     * <b>is_deleted 必须保持 0</b>：它带 {@code @TableLogic}，置 1 会让管理端、登录、
     * 社区作者解析等所有 MyBatis-Plus 查询都过滤掉这一行，等于把账号"删没了"，
     * 反而无法证明"这个 ID 已被注销"。终态用 account_status 表达，PII 用置空表达。
     */
    @Update("UPDATE user_account SET openid = NULL, union_id = NULL, phone = NULL, email = NULL, "
            + "last_login_ip = NULL, username = '已注销用户', account_status = 3, is_deleted = 0 "
            + "WHERE id = #{userId}")
    int tombstoneById(@Param("userId") Long userId);

    /**
     * 管理端取消注销：账号侧乐观锁，仅当仍处于注销中(4)时还原为 pre_status。
     *
     * <p>自定义 SQL 不会自动拼 {@code @TableLogic} 条件，因此必须显式带上 {@code is_deleted = 0}。
     * 命中 0 行说明状态已被并发登录恢复、Worker 或其它管理员改变，调用方按 409 处理。
     */
    @Update("UPDATE user_account SET account_status = #{preStatus} "
            + "WHERE id = #{userId} AND account_status = 4 AND is_deleted = 0")
    int casRecoverFromCancellation(@Param("userId") Long userId, @Param("preStatus") int preStatus);
}
