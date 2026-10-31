package com.ftllight.flashsalebackend.common;

import lombok.Getter;

@Getter
public class GlobalException extends RuntimeException {

    private final CodeMsg codeMsg;

    public GlobalException(CodeMsg codeMsg) {
        super(codeMsg.getMsg());
        this.codeMsg = codeMsg;
    }
}
