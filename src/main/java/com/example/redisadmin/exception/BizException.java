package com.example.redisadmin.exception;

import lombok.Getter;

/**
 * 业务异常，携带 5 位 {@link ErrorCode}。
 * 统一 HTTP 200 + envelope 返回（见 {@link GlobalExceptionHandler}），前端只看 code。
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Object[] args;

    public BizException(ErrorCode errorCode, Object... args) {
        super(errorCode.formatMessage(args));
        this.errorCode = errorCode;
        this.args = args;
    }

    public int getCode() {
        return errorCode.getCode();
    }

    /**
     * 便捷构造：直接给定完整 msg（细分语义走文案，不新增错误码）。
     */
    public static BizException of(ErrorCode errorCode, String msg) {
        return new BizException(errorCode, msg);
    }
}
