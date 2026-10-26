package com.threadfit.glucopilot.profile.health.service;

import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthSaveRequest;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthView;

/**
 * 健康档案（user_health）服务：健康状态、用药历史、血糖控制目标。
 * 单行表、一用户一份活跃档案；保存为全量提交（null/空白字段 = 清空回未填写）。
 * user 模块的 /users/me 通过本接口聚合健康数据，只依赖接口不依赖实现，
 * 保证 user → profile.health 单向无环的模块边界。
 */
public interface HealthProfileService {

    /** 读取健康档案视图；行缺失时兜底新建（历史数据兼容），返回未填写(0)的默认档案。 */
    HealthView healthViewOf(Long userId);

    /**
     * 全量保存健康档案（幂等覆盖，非局部补丁）。
     * 入参 null/空白字段会被显式置空；diabetesTypeText 按 diabetesType 服务端派生写入，
     * 忽略客户端传入的自由文本；diagnosedYm 不得晚于当前月。
     * @return 保存后的健康档案视图
     */
    HealthView saveHealth(Long userId, HealthSaveRequest request);

    /**
     * 账号注销：物理删除健康档案。
     * <p>这是敏感健康数据，注销后不留任何行（含逻辑删除态）。
     */
    void purgeAccountData(Long userId);
}
