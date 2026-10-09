package com.example.redisadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 应用入口。
 * <p>调度由 {@code SchedulingConfig} 显式开启（不放在主类，保持调度职责归属清晰）。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RedisAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedisAdminApplication.class, args);
    }
}
