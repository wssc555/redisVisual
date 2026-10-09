package com.example.redisadmin.config;

import com.example.redisadmin.web.ProfileArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * CORS 白名单 + 注册 {@link ProfileArgumentResolver}。
 * <p>白名单按设计 §11.4 固定三处：Vite 开发端口 + Tauri WebView 的两个 origin
 * （为桌面化预留）。<b>不使用通配符</b> —— 本服务持有全部已配置 Redis 实例的读写权限，
 * 暴露面必须收紧（见 README 部署红线）。</p>
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
                        "https://tauri.localhost",
                        "https://tauri://localhost")
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
