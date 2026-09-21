package com.github.lechandonga.accountledgerjava.support;

import com.github.lechandonga.accountledgerjava.exception.InvalidRequestException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 游标编解码：内部使用单调自增 id，对外用 Base64URL 不透明字符串，
 * 防止调用方伪造/猜测游标。非法游标抛 InvalidRequestException（错误码 INVALID_CURSOR）。
 */
public final class PageCursor {

    private static final String PREFIX = "le:";

    private PageCursor() {
    }

    /** 将上一页最后一条流水的 id 编码为下一页游标 */
    public static String encode(long lastId) {
        if (lastId <= 0) {
            throw new InvalidRequestException("非法游标: id 必须为正数");
        }
        byte[] raw = (PREFIX + lastId).getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** 解析游标；null/空白表示首页（afterId=0） */
    public static long decodeOrZero(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0L;
        }
        String decoded;
        try {
            byte[] raw = Base64.getUrlDecoder().decode(cursor);
            decoded = new String(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("非法分页游标: 无法解码");
        }
        if (!decoded.startsWith(PREFIX)) {
            throw new InvalidRequestException("非法分页游标: 前缀不匹配");
        }
        try {
            long id = Long.parseLong(decoded.substring(PREFIX.length()));
            if (id <= 0) {
                throw new InvalidRequestException("非法分页游标: id 必须为正数");
            }
            return id;
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("非法分页游标: id 不是数字");
        }
    }
}
