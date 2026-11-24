package com.threadfit.glucopilot.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.user.entity.UserCancelLogEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 注销审计日志（只追加）。
 *
 * <p>写入统一走 {@link #insertIgnore(UserCancelLogEntity)}：唯一键
 * {@code (cancellation_id, action_type)} 兜底，重复事件静默忽略而不是抛异常。
 * 这样 worker 重试、前端重复点击都不会让主业务事务因审计重复而失败。
 */
@Mapper
public interface UserCancelLogMapper extends BaseMapper<UserCancelLogEntity> {

    @Insert("INSERT IGNORE INTO user_cancel_logs "
            + "(user_id, cancellation_id, action_type, from_status, to_status, "
            + " operator_type, operator_id, remark, request_id, client_ip) "
            + "VALUES (#{userId}, #{cancellationId}, #{actionType}, #{fromStatus}, #{toStatus}, "
            + " #{operatorType}, #{operatorId}, #{remark}, #{requestId}, #{clientIp})")
    int insertIgnore(UserCancelLogEntity row);
}
