package com.threadfit.glucopilot.community.recommendation.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** SQL见resources下同包路径的AdminRecommendationMapper.xml。 */
@Mapper
public interface AdminRecommendationMapper {

    Map<String, Object> user(@Param("id") long id);

    List<Map<String, Object>> tags();

    List<Map<String, Object>> articleTags(@Param("ids") List<Long> ids);

    List<Map<String, Object>> authors(@Param("ids") List<Long> ids);
}
