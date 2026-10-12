package com.threadfit.glucopilot.meal.service;

import com.threadfit.glucopilot.meal.dto.MealDtos;
import com.threadfit.glucopilot.meal.vo.MealVos;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;

/** 饮食记录：保存（覆盖写入）、按日查询、删除与消费方读取。 */
public interface MealRecordService {

    MealVos.SaveResult save(Long userId, MealDtos.SaveRequest request);

    MealVos.DayMeals listDay(Long userId, LocalDate recordDate);

    void remove(Long userId, Long id);

    /** 批量统计活跃记录数（user_id → 数量），无记录用户不返回键。 */
    Map<Long, Long> countActiveByUserIds(Collection<Long> userIds);

    /** 管理端健康档案用：窗口内饮食汇总（记录天数 / 均碳 / 均热 / 高频食物）。 */
    MealVos.ArchiveSummary archiveSummary(Long userId, LocalDate from, LocalDate to);
}
