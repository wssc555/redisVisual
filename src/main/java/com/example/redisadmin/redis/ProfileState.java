package com.example.redisadmin.redis;

/**
 * profile 的运行时连接状态。纯内存态，不落库。
 * 存在的意义：让「配置是否存在」（库）与「现在连不连得上」（内存）分离。
 */
public enum ProfileState {
    /**
     * 心跳 PING 通过
     */
    CONNECTED,
    /**
     * 心跳失败或尚未成功建立过连接
     */
    OFFLINE
}
