package com.example.redisadmin.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class KeyRenameDTO {
    @NotBlank(message = "新Key不能为空")
    private String newKey;
}