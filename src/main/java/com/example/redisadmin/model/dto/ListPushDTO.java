package com.example.redisadmin.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ListPushDTO {
    @NotNull(message = "值列表不能为空")
    @Size(min = 1, message = "至少需要一个值")
    private List<String> values;

    private Integer ttlSeconds;
}