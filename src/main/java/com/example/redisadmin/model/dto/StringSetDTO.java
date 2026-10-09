package com.example.redisadmin.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class StringSetDTO {
    @NotBlank(message = "Key不能为空")
    private String key;

    @NotBlank(message = "Value不能为空")
    private String value;

    private Integer ttlSeconds;
}