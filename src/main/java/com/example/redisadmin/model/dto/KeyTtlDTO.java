package com.example.redisadmin.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class KeyTtlDTO {
    @NotNull(message = "TTL不能为空")
    private Long ttlSeconds;
}