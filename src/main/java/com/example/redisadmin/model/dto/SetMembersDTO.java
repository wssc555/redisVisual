package com.example.redisadmin.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class SetMembersDTO {
    @NotNull(message = "成员列表不能为空")
    @Size(min = 1, message = "至少需要一个成员")
    private List<String> members;

    private Integer ttlSeconds;
}