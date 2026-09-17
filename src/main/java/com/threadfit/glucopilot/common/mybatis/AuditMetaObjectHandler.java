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

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "gmtModified", LocalDateTime.class, LocalDateTime.now());
        strictUpdateFill(metaObject, "updatedBy", Long.class, RequestContext.operatorIdOrZero());
    }
}
