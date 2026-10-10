package com.holdhundredyear.common;

import lombok.Data;

import java.util.List;

/**
 * 通用分页结果。records 为当前页记录，total 为筛选后总条数（跨页不变）。
 */
@Data
public class PageResult<T> {

    private List<T> records;

    private long total;

    public PageResult() {
    }

    public PageResult(List<T> records, long total) {
        this.records = records;
        this.total = total;
    }
}