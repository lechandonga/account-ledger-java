package com.github.lechandonga.accountledgerjava.domain;

import java.util.List;

/**
 * 游标分页结果。nextCursor 为 null 表示没有更多数据。
 */
public record PageResult<T>(
        List<T> items,
        String nextCursor,
        boolean hasMore) {
}
