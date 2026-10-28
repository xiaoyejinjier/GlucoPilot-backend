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
}
