package com.ftllight.flashsalebackend.common;

public enum RedisTypeString {

    STOCK_PREFIX("seckill:stock:"),
    SOLD_OUT_PREFIX("seckill:soldout:"),
    USER_ORDER_PREFIX("seckill:order:"),
    RESULT_PREFIX("seckill:result:");

    private final String value;

    RedisTypeString(String value) {
        this.value = value;
    }

    public String key(Object... parts) {
        StringBuilder builder = new StringBuilder(value);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) builder.append(':');
            builder.append(parts[i]);
        }
        return builder.toString();
    }
}
