package com.threadfit.glucopilot.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.content.entity.CmsNews;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CmsNewsMapper extends BaseMapper<CmsNews> {
    @Select("SELECT * FROM cms_news WHERE id=#{id} AND is_deleted=0 FOR UPDATE")
    CmsNews selectForAction(@Param("id") Long id);
}
