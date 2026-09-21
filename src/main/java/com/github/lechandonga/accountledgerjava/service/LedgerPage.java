package com.github.lechandonga.accountledgerjava.service;

import com.github.lechandonga.accountledgerjava.domain.LedgerEntry;

import java.util.List;

/**
 * 一页流水。nextCursor 为 null 表示没有更多数据；
 * 否则以其为入参取下一页。游标基于自增主键，分页期间
 * 有新流水写入不会漏读或重复读取。
 */
public record LedgerPage(List<LedgerEntry> entries, Long nextCursor) {
}
