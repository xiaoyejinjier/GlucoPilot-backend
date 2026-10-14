package com.threadfit.glucopilot.food.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 食品分类数据库实体，对应 {@code food_category} 表。
 *
 * <p>Entity 负责描述 Java 属性与数据库列之间的映射。继承 {@link BaseEntity}
 * 后自动拥有主键、创建/修改审计字段和逻辑删除字段。</p>
 */
@Data
// 生成 equals/hashCode 时同时考虑 BaseEntity 中的字段。
@EqualsAndHashCode(callSuper = true)
@TableName("food_category")
public class FoodCategory extends BaseEntity {

    /** 分类名称，对应 category_name。 */
    private String categoryName;

    /** 分类配图地址；未选择时由客户端展示默认图片。 */
    private String imageUrl;

    /** 分类排序，对应 sort_no。 */
    private Integer sortNo;

    /** 是否启用：0=停用，1=启用。 */
    private Integer isEnabled;
}
