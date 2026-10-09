package com.example.redisadmin.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ZSetMemberDTO {
    @NotNull(message = "成员不能为空")
    private String member;

    @NotNull(message = "分数不能为空")
    private Double score;

    private Integer ttlSeconds;
}