package com.threadfit.glucopilot.common.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 统一分页入参。pageSize 上限 100，与接口设计文档一致。
 */
@Data
public class PageQuery {

    @Min(1)
    private int pageNum = 1;

    @Min(1)
    @Max(100)
    private int pageSize = 20;

    /** 供手工 SQL 使用；MyBatis-Plus 一般直接传 pageNum/pageSize。 */
    public long offset() {
        return (long) (pageNum - 1) * pageSize;
    }
}
