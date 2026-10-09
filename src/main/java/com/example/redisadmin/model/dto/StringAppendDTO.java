package com.example.redisadmin.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class StringAppendDTO {
    @NotBlank(message = "追加的值不能为空")
    private String value;
}