package com.threadfit.glucopilot.common.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务表通用审计字段（对齐 DDL：gmt_* / is_deleted / created_by…）。
 * ops_audit_log 等只追加表请用 {@link AppendOnlyEntity}。
 */
@Data
public abstract class BaseEntity {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime gmtCreate;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long updatedBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime gmtModified;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long deletedBy;

    private LocalDateTime deletedAt;

    /** 0 未删 / 1 已删；与 deletedAt、deletedBy 需成对维护。 */
    @TableLogic
    private Integer isDeleted;
}
