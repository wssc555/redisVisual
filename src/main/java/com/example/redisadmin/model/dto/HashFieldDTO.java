package com.example.redisadmin.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class HashFieldDTO {
    @NotBlank(message = "字段名不能为空")
    private String field;

    @NotNull(message = "值不能为空")
    private String value;

    private Integer ttlSeconds;
}