package com.example.redisadmin.config;

import com.example.redisadmin.web.ProfileArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * CORS 白名单 + 注册 {@link ProfileArgumentResolver}。
 * <p>白名单固定三条：Vite 开发端口两条 + 桌面壳自定义协议的稳定 origin 一条。
 * <b>不使用通配符</b> —— 本服务持有全部已配置 Redis 实例的读写权限，
 * 暴露面必须收紧（见 README 部署红线）。</p>
 *
 * <p>桌面壳 origin 与具体壳实现解耦：Electron 以
 * {@code protocol.registerSchemesAsPrivileged} 注册<b>标准协议</b> {@code app://}，
 * 渲染进程 origin 稳定为 {@code app://localhost}（若注册为非标准协议，Chromium 会把
 * origin 序列化成 {@code null}，届时只能开 {@code null} 通配，等于放弃同源策略）。
 * 换任意壳只需替换这一条 origin，其余逻辑不动。</p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ProfileArgumentResolver profileArgumentResolver;

    public WebMvcConfig(ProfileArgumentResolver profileArgumentResolver) {
        this.profileArgumentResolver = profileArgumentResolver;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(
                        "http://localhost:5173",
                        "http://127.0.0.1:5173",
                        // 桌面壳（Electron app:// 标准协议）的稳定 origin
                        "app://localhost")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(profileArgumentResolver);
    }
}
