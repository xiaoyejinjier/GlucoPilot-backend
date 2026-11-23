package com.threadfit.glucopilot.meal.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 饮食记录主表实体（meal_record，见 V1 L466-497）。
 * <p>营养快照保存时点固化、不可变；nodeCode 须为餐点（is_meal=1）。
 * 覆盖语义依赖 (user_id, record_date, node_code, is_deleted) 唯一键；预测三列仅落库不重算。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("meal_record")
public class MealRecord extends BaseEntity {

    private Long userId;
    private LocalDate recordDate;
    private String nodeCode;
    /** 餐食照片 ID（file 侧关联），可空 */
    private Long mediaId;
    /** 食物简述快照 */
    private String foodsText;
    /** 碳水克数快照 */
    private BigDecimal carbsG;
    /** 饥饿感：1饱腹 2一般 3微饿，可空 */
    private Integer hungerLevel;
    /** 补餐备注，可空 */
    private String supplementText;
    /** 已下线（阶段 4 停写，列暂留至清理迁移）：预测当前血糖（mmol/L），可空 */
    private BigDecimal predictNow;
    /** 已下线（阶段 4 停写，列暂留至清理迁移）：预测建议血糖（mmol/L），可空 */
    private BigDecimal predictSuggest;
    /** 已下线（阶段 4 停写，列暂留至清理迁移）：预测建议文案，可空 */
    private String suggestText;
    /** 已下线（阶段 4 停写，列暂留至清理迁移）：算法版本号，可空 */
    private String algorithmVersion;
    /** 已下线（阶段 4 停写，列暂留至清理迁移）：推荐状态，可空 */
    private String recommendationStatus;
    /** 识别模型标识（如 qwen-vl），可空 */
    private String recognitionModel;
    /** 识别完成时间，可空 */
    private LocalDateTime recognitionAt;
    /** 用户确认时间，可空 */
    private LocalDateTime confirmedAt;
    /** 分析状态：0待分析 1已生成 2失败；可空 = 历史记录未分析 */
    private Integer analysisStatus;
    /** 保存时引用的识别快照令牌（V90），可空；媒体级溯源，不证明逐条明细 */
    private String analysisToken;
    /** 整餐档位：LOW|MODERATE|HIGH|UNKNOWN，可空 */
    private String analysisTier;
    /** 整餐摘要（通用文案），可空 */
    private String analysisSummary;
    /** 判定所用规则版本，历史可追溯，可空 */
    private String analysisRuleVersion;
    /** 分析生成时间，可空 */
    private LocalDateTime analysisGeneratedAt;
    /** 记录时刻（服务端写入，防伪） */
    private LocalDateTime recordedAt;
}
