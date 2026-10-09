package com.threadfit.glucopilot.common.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * 统一分页出参：pageNum / pageSize / total / pages / records。
 * 字典、Banner、分类等全量接口请直接返回 List，不要套本结构。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private long pageNum;
    private long pageSize;
    private long total;
    private long pages;
    private List<T> records;

    public static <T> PageResult<T> empty(long pageNum, long pageSize) {
        return new PageResult<>(pageNum, pageSize, 0, 0, Collections.emptyList());
    }

    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(
                page.getCurrent(),
                page.getSize(),
                page.getTotal(),
                page.getPages(),
                page.getRecords() == null ? Collections.emptyList() : page.getRecords()
        );
    }

    public static <T> PageResult<T> of(long pageNum, long pageSize, long total, List<T> records) {
        long pages = pageSize <= 0 ? 0 : (total + pageSize - 1) / pageSize;
        return new PageResult<>(
                pageNum,
                pageSize,
                total,
                pages,
                records == null ? Collections.emptyList() : records
        );
    }
}
