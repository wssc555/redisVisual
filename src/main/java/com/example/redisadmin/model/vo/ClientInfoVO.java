package com.example.redisadmin.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientInfoVO {
    private String id;
    private String addr;
    private String db;
    private String age;
    private String idle;
    private String cmd;
}
