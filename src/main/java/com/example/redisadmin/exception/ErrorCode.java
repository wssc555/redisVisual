package com.example.redisadmin.exception;

/**
 * 5 位语义化错误码（对齐 kafkaVisual5 同号同义）。
 * 原则：不新增错误码，细分语义一律走 msg 文案。
 */
public enum ErrorCode {
    SUCCESS(0, "ok"),
    /**
     * 校验失败：参数不合法、名称重复、集群选 db>0、大 Key 拒绝全量、元素超上限、模式条件字段缺失。
     */
    VALIDATION_ERROR(40001, "{0}"),
    /**
     * 连接配置不存在。
     */
    PROFILE_NOT_FOUND(40401, "连接配置不存在: {0}"),
    /**
     * Key 不存在。
     */
    KEY_NOT_FOUND(40402, "Key 不存在: {0}"),
    /**
     * 类型冲突：key 实际类型与请求操作类型不符。
     */
    TYPE_MISMATCH(40902, "类型冲突: 期望 {0}, 实际 {1}"),
    /**
     * 连接超时/不可用：建连失败、命令超时、哨兵找不到 master、cluster_state != ok。
     */
    REDIS_UNAVAILABLE(50302, "Redis 不可用: {0}"),
    /**
     * Redis 命令执行失败（非超时类 RedisException）。
     */
    REDIS_COMMAND_FAILED(50001, "Redis 命令执行失败: {0}"),
    /**
     * 兜底：未预期系统异常。
     */
    SYSTEM_ERROR(50000, "系统异常: {0}");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String formatMessage(Object... args) {
        if (args == null || args.length == 0) {
            return message;
        }
        return java.text.MessageFormat.format(message, args);
    }
}
