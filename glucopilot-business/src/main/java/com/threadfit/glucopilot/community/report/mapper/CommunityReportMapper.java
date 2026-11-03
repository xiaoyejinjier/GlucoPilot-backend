package com.threadfit.glucopilot.community.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.community.report.entity.CommunityReport;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/** Community persistence. All values are bound parameters; row locks share Spring transactions. */
@Mapper
public interface CommunityReportMapper extends BaseMapper<CommunityReport> {

    @Select(
            "SELECT COUNT(*) FROM user_account WHERE id=#{id} AND is_deleted=0 AND account_status=1"
                    + " ")
    Long countActiveUser(@Param("id") long id);

    @Select("SELECT COUNT(*) FROM community_report WHERE reporter_id=#{viewer} ")
    Long countOwnedReports(@Param("viewer") long viewer);

    @Select(
            "SELECT * FROM community_report WHERE reporter_id=#{viewer} ORDER BY id DESC LIMIT"
                    + " #{limit} OFFSET #{offset} ")
    List<Map<String, Object>> selectOwnedReports(
            @Param("viewer") long viewer, @Param("limit") int limit, @Param("offset") long offset);

    @Select("SELECT * FROM community_report WHERE id=#{id} AND reporter_id=#{viewer} ")
    List<Map<String, Object>> selectOwnedReport(@Param("id") long id, @Param("viewer") long viewer);

    @Update(
            "UPDATE community_report SET feedback_read_at=CURRENT_TIMESTAMP WHERE id=#{id} AND"
                + " reporter_id=#{viewer} AND state IN ('RESOLVED','DISMISSED') AND resolved_at IS"
                + " NOT NULL AND feedback_read_at IS NULL ")
    int markFeedbackRead(@Param("id") long id, @Param("viewer") long viewer);
}
