package com.example.redisadmin.model.vo;

/**
 * 凭据存在性。仅表达「是否配置」，<b>永不回显明文或可逆向值</b>（见设计 §5.2）。
 */
public enum CredentialPresence {
    /**
     * 未配置密码
     */
    NONE,
    /**
     * 已配置密码（密文落库，接口不回显）
     */
    PRESENT
}
