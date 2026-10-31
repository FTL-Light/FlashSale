package com.ftllight.flashsalebackend.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CodeMsg {

    SUCCESS(0, "success"),
    SERVER_ERROR(500, "服务器异常"),

    PARAM_ERROR(500101, "参数校验失败"),
    UNAUTHORIZED(500102, "未登录或Token无效"),

    STOCK_EMPTY(500201, "库存不足"),
    SECKILL_REPEATED(500202, "请勿重复购买"),
    SECKILL_SOLD_OUT(500203, "已售罄"),
    SECKILL_NOT_STARTED(500204, "秒杀未开始"),
    SECKILL_ENDED(500205, "秒杀已结束"),
    RATE_LIMIT(500206, "操作过于频繁，请稍后再试"),

    USER_NOT_FOUND(500301, "用户不存在"),
    PASSWORD_ERROR(500302, "密码错误");

    private final int code;
    private final String msg;
}
