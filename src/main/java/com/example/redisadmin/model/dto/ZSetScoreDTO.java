package com.example.redisadmin.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ZSetScoreDTO {
    @NotNull(message = "分数不能为空")
    private Double score;
}