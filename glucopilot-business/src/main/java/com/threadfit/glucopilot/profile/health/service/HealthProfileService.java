package com.threadfit.glucopilot.profile.health.service;

import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthSaveRequest;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthView;

/**
 * 健康档案（user_health）：健康状态、用药历史、血糖控制目标。
 * user 模块的 /users/me 通过本接口聚合健康数据，只依赖接口不依赖实现。
 */
public interface HealthProfileService {

    /** 读取健康档案视图；行缺失时兜底新建（历史数据兼容）。 */
    HealthView healthViewOf(Long userId);

    /** 全量保存：null 字段显式置空，diabetesTypeText 按 diabetesType 派生写入。 */
    HealthView saveHealth(Long userId, HealthSaveRequest request);
}
