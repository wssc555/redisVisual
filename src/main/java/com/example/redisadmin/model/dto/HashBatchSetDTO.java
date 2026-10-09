package com.example.redisadmin.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Hash 批量写入。复用 {@link HashFieldDTO} 的 field/value 结构，
 * 避免用 {@code Map<String,Object>} 承载请求体（失去校验与类型安全）。
 */
@Data
public class HashBatchSetDTO {

    @NotEmpty(message = "字段列表不能为空")
    @Size(max = 1000, message = "单次批量写入字段数不能超过 1000")
    @Valid
    private List<HashFieldDTO> entries;

    private Integer ttlSeconds;
}
