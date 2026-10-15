package com.threadfit.glucopilot.ai.scene;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code ai_scene_config} 的实体（V17 建表）：场景 → 模型 与 场景级参数的绑定。
 * <p>
 * 取代的是一部分 yaml：{@code ai.scenes.<scene>.model-code / temperature / max-tokens / thinking}。
 * yaml 仍保留为兜底，DB 行存在且字段非空时优先。
 */
@Data
@TableName("ai_scene_config")
public class AiSceneConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 场景编码，见 {@code SceneCode.getCode()}。 */
    private String sceneCode;

    /** 绑定的模型编码（引用 ai_model_config.model_code）；NULL = 用通道默认。 */
    private String modelCode;

    private BigDecimal temperature;

    private Integer maxTokens;

    private Boolean thinking;

    private Integer isEnabled;

    private Long configVersion;

    private String remark;

    private Long createdBy;

    private LocalDateTime gmtCreate;

    private Long updatedBy;

    private LocalDateTime gmtModified;

    @TableLogic
    private Integer isDeleted;
}