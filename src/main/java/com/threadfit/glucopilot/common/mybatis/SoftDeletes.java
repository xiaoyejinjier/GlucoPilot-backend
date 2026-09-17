package com.threadfit.glucopilot.common.mybatis;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.model.BaseEntity;

import java.time.LocalDateTime;

/**
 * 逻辑删除：同时写 is_deleted / deleted_at / deleted_by，满足 DDL CHECK。
 * 勿只用 {@code deleteById}（MP 默认只改 is_deleted）。
 */
public final class SoftDeletes {

    private SoftDeletes() {
    }

    public static <T extends BaseEntity> void mark(
            BaseMapper<T> mapper,
            SFunction<T, ?> idGetter,
            Long id,
            SFunction<T, Integer> isDeletedGetter,
            SFunction<T, LocalDateTime> deletedAtGetter,
            SFunction<T, Long> deletedByGetter) {
        LocalDateTime now = LocalDateTime.now();
        long operator = RequestContext.operatorIdOrZero();
        mapper.update(null, new LambdaUpdateWrapper<T>()
                .eq(idGetter, id)
                .set(isDeletedGetter, 1)
                .set(deletedAtGetter, now)
                .set(deletedByGetter, operator));
    }
}
