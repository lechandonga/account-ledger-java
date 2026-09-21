package com.github.lechandonga.accountledgerjava.support;

import com.github.lechandonga.accountledgerjava.exception.InvalidRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 金额工具：对外 BigDecimal（元，2 位小数），对内 long（分） */
public final class Money {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private Money() {
    }

    /** 元 -> 分，超过 2 位小数直接拒绝（不做隐式舍入，保证可解释） */
    public static long toCents(BigDecimal yuan) {
        if (yuan == null) {
            throw new InvalidRequestException("金额不能为空");
        }
        BigDecimal scaled;
        try {
            scaled = yuan.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new InvalidRequestException("金额精度非法，最多支持 2 位小数: " + yuan);
        }
        return scaled.multiply(HUNDRED).longValueExact();
    }

    /** 分 -> 元 */
    public static BigDecimal toYuan(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    /** 校验金额必须为正且精度不超过 2 位小数 */
    public static void requirePositive(BigDecimal yuan) {
        if (yuan == null) {
            throw new InvalidRequestException("金额不能为空");
        }
        if (yuan.signum() <= 0) {
            throw new InvalidRequestException("金额必须大于 0: " + yuan);
        }
        if (yuan.stripTrailingZeros().scale() > 2) {
            throw new InvalidRequestException("金额精度非法，最多支持 2 位小数: " + yuan);
        }
    }
}
