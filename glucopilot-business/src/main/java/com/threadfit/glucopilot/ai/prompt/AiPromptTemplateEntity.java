package com.threadfit.glucopilot.ai.prompt;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * {@code ai_prompt_template} 的实体（V1 建表、V16 扩列）。
 * <p>
 * 一行 = 某个场景的<b>一个版本</b>。版本追加式：编辑历史版本不覆盖，而是新增一行；
 * {@code uk_scene_ver_active(scene_code, version_no, is_deleted)} 保证同场景版本号不重复。
 */
@Data
@TableName("ai_prompt_template")
public class AiPromptTemplateEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 场景编码，见 {@code SceneCode.getCode()}（如 CHAT / CHAT_IMAGE / MEAL_ANALYSIS / OCR_GLUCOSE）。 */
    private String sceneCode;

    /** 模板名称（给运营看的中文名）。 */
    private String templateName;

    /** 提示词正文（含 ST 语法 {变量} 占位符）。 */
    private String promptBody;

    /** 变量清单（JSON 数组字符串），保存前据此试渲染校验。 */
    private String variablesJson;

    /** 绑定模型编码；NULL = 用通道默认（历史上也用于"场景绑模型"，现由 ai_scene_config 承担）。 */
    private String modelCode;

    /** 版本号（整数）；对外展示为 v{versionNo}。 */
    private Integer versionNo;

    /** 是否启用；同场景只允许一行为 1（服务层保证）。 */
    private Integer isEnabled;

    /** 改动说明（审计与回溯用）。 */
    private String changeNote;

    /** 乐观锁版本。 */
    private Long configVersion;

    private Long createdBy;

    private LocalDateTime gmtCreate;

    private Long updatedBy;

    private LocalDateTime gmtModified;

    @TableLogic
    private Integer isDeleted;
}