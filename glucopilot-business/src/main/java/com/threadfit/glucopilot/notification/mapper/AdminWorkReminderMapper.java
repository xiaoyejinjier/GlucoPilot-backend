package com.threadfit.glucopilot.notification.mapper;

import com.threadfit.glucopilot.notification.entity.WorkReminderCount;
import com.threadfit.glucopilot.user.feedback.FeedbackReplyState;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 仅统计服务器授权允许查看的队列，Service决定是否调用对应查询。 */
@Mapper
public interface AdminWorkReminderMapper {
    @Select(
            "SELECT COUNT(*) total,MAX(created_at) latest_at FROM community_report WHERE"
                + " state='OPEN'")
    WorkReminderCount countReports();

    @Select(
            "SELECT COUNT(*) total,MAX(gmt_create) latest_at FROM feedback WHERE "
                    + FeedbackReplyState.PENDING_SQL
                    + " AND is_deleted=0")
    WorkReminderCount countFeedback();

    @Select(
            """
            SELECT COUNT(*) total,MAX(gmt_create) latest_at FROM food_nutrition
            WHERE nutrition_source='AI_ESTIMATE' AND shelf_status=0 AND is_deleted=0
            """)
    WorkReminderCount countPendingFoods();
}
