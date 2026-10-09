package com.example.redisadmin.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启调度（心跳探活 / 趋势采样）。
 * 从主类迁出，使调度职责归属显式。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
