package com.threadfit.glucopilot.ai.prompt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import com.threadfit.glucopilot.ai.prompt.entity.AiPromptTemplateEntity;
import com.threadfit.glucopilot.ai.prompt.service.AdminAiPromptService;

/**
 * 提示词模板读写。
 * <p>
 * 运行时只关心"每个场景当前启用的那一版"，因此这里只需要两条自定义语句；
 * "同场景唯一启用"是业务约束（MySQL 无部分唯一索引），由 {@link AiPromptTemplateMapper#deactivateOthers}
 * 在启用时同事务保证。
 */
public interface AiPromptTemplateMapper extends BaseMapper<AiPromptTemplateEntity> {

    /** 运行时加载：所有启用且未删除的模板（按场景排序，结果稳定可比）。 */
    @Select("SELECT * FROM ai_prompt_template WHERE is_deleted = 0 AND is_enabled = 1 "
            + "ORDER BY scene_code ASC, version_no DESC")
    List<AiPromptTemplateEntity> selectEnabled();

    /** 启用某版本时，同场景其它版本一并停用（保证线上唯一生效版本）。 */
    @Update("UPDATE ai_prompt_template SET is_enabled = 0, config_version = config_version + 1 "
            + "WHERE scene_code = #{sceneCode} AND id <> #{id} AND is_deleted = 0 AND is_enabled = 1")
    int deactivateOthers(@Param("sceneCode") String sceneCode, @Param("id") Long id);

    /** 同场景已用过的最大版本号 +1，用于"新增版本"时的默认值。 */
    @Select("SELECT COALESCE(MAX(version_no), 0) FROM ai_prompt_template "
            + "WHERE scene_code = #{sceneCode} AND is_deleted = 0")
    int selectMaxVersionNo(@Param("sceneCode") String sceneCode);

    /** 乐观锁更新（内容字段 + {@code version_no}：草稿允许改版本号，见 AdminAiPromptService.update）。 */
    @Update("UPDATE ai_prompt_template SET template_name = #{e.templateName}, prompt_body = #{e.promptBody}, "
            + "variables_json = #{e.variablesJson}, model_code = #{e.modelCode}, "
            + "version_no = #{e.versionNo}, "
            + "change_note = #{e.changeNote}, is_enabled = #{e.isEnabled}, "
            + "config_version = config_version + 1 "
            + "WHERE id = #{e.id} AND is_deleted = 0 AND config_version = #{expectedVersion}")
    int updateWithVersion(@Param("e") AiPromptTemplateEntity entity, @Param("expectedVersion") Long expectedVersion);

    /** 启用/停用（也走版本条件，避免与并发的整体保存互相踩）。 */
    @Update("UPDATE ai_prompt_template SET is_enabled = #{enabled}, config_version = config_version + 1 "
            + "WHERE id = #{id} AND is_deleted = 0 AND config_version = #{expectedVersion}")
    int updateEnabled(@Param("id") Long id, @Param("enabled") int enabled, @Param("expectedVersion") Long expectedVersion);

    /**
     * 逻辑删除<b>草稿</b>版本（{@code is_enabled = 0}）。
     * <p>
     * 把"只删草稿"写进 SQL 的 WHERE 而不是只靠服务层判断：并发的"启用"操作与"删除"撞在一起时，
     * 这条语句会因 {@code is_enabled} 不再为 0 而返回 0 行，删除自然失败——
     * 这正是我们要的（绝不能把正在生效的那一版删掉，否则该场景会静默回落到 classpath 提示词）。
     * <p>
     * 唯一键 {@code uk_scene_ver_active(scene_code, version_no, is_deleted)} 包含 is_deleted，
     * 因此"删掉 v2 之后再建一个 v2"是允许的。
     */
    @Update("UPDATE ai_prompt_template SET is_deleted = 1, deleted_at = NOW(3), deleted_by = #{deletedBy}, "
            + "config_version = config_version + 1 "
            + "WHERE id = #{id} AND is_deleted = 0 AND is_enabled = 0 AND config_version = #{expectedVersion}")
    int logicalDeleteDraft(@Param("id") Long id, @Param("deletedBy") Long deletedBy,
                           @Param("expectedVersion") Long expectedVersion);
}