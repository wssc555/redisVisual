package com.example.redisadmin.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ListSetByIndexDTO {
    @NotNull(message = "值不能为空")
    private String value;
}