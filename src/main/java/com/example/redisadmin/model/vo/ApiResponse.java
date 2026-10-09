package com.example.redisadmin.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应信封 {@code {code, msg, data}}。
 * 全部端点 HTTP 200，错误也走此结构（对齐 kafkaVisual5），前端只看 code。
 * <p>本类是 3 类 {@code @JsonInclude(NON_NULL)} 白名单之一（另两类：KeyDetailVO / KeyScanVO），
 * 其余 VO 的 null 字段显式输出。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private int code;
    private String msg;
    private T data;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(0, "ok", null);
    }

    public static <T> ApiResponse<T> error(int code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }
}
