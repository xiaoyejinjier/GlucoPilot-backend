package com.threadfit.glucopilot.ai.scene;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 场景配置读写（运行时只读启用行；后台按 id 做乐观锁更新）。 */
public interface AiSceneConfigMapper extends BaseMapper<AiSceneConfigEntity> {

    @Select("SELECT * FROM ai_scene_config WHERE is_deleted = 0 AND is_enabled = 1 ORDER BY scene_code ASC")
    List<AiSceneConfigEntity> selectEnabled();

    @Update("UPDATE ai_scene_config SET model_code = #{e.modelCode}, temperature = #{e.temperature}, "
            + "max_tokens = #{e.maxTokens}, thinking = #{e.thinking}, is_enabled = #{e.isEnabled}, "
            + "remark = #{e.remark}, config_version = config_version + 1 "
            + "WHERE id = #{e.id} AND is_deleted = 0 AND config_version = #{expectedVersion}")
    int updateWithVersion(@Param("e") AiSceneConfigEntity entity, @Param("expectedVersion") Long expectedVersion);
}