package com.example.redisadmin.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 {@code /api/c/{id}} 段中的连接配置 id，由 {@link ProfileArgumentResolver} 统一解析。
 * 对应 kafkaVisual5 的 {@code @ClusterId}。
 * <p>解析失败（缺失/非数字）→ 40001；存在性校验在 service 层 → 40401。</p>
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProfileId {
}
