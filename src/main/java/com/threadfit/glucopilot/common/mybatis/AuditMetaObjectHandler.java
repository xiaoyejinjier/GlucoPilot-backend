package com.threadfit.glucopilot.common.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.threadfit.glucopilot.common.context.RequestContext;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 插入/更新时自动填充审计字段；操作人来自 RequestContext，匿名为 0。
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        long operatorId = RequestContext.operatorIdOrZero();

        strictInsertFill(metaObject, "gmtCreate", LocalDateTime.class, now);
        strictInsertFill(metaObject, "gmtModified", LocalDateTime.class, now);
        strictInsertFill(metaObject, "createdBy", Long.class, operatorId);
        strictInsertFill(metaObject, "updatedBy", Long.class, operatorId);

        // BaseEntity 默认未删；若实体未显式设值则补 0
        Object isDeleted = getFieldValByName("isDeleted", metaObject);
        if (isDeleted == null) {
            strictInsertFill(metaObject, "isDeleted", Integer.class, 0);
        }
    }

    /**
     * 更新时刷新审计字段。
     * <p>
     * 这里不能用 strictUpdateFill：它只在字段为 null 时才填，而更新用的实体通常是从库里
     * 读出来的（gmtModified 已有值），于是旧时间戳被原样写进 SET 列表，MySQL 的
     * ON UPDATE CURRENT_TIMESTAMP 又因为「该列被显式赋值」而不生效——结果 gmt_modified
     * 永远停在创建那一刻，审计链断掉。所以改成无条件覆盖。
     * <p>
     * AppendOnlyEntity（只追加表）没有这两个字段，用 hasSetter 挡一下。
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        setIfPresent(metaObject, "gmtModified", LocalDateTime.now());
        setIfPresent(metaObject, "updatedBy", RequestContext.operatorIdOrZero());
    }

    private static void setIfPresent(MetaObject metaObject, String field, Object value) {
        if (metaObject.hasSetter(field)) {
            metaObject.setValue(field, value);
        }
    }
}
