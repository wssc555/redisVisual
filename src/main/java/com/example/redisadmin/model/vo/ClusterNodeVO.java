package com.example.redisadmin.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterNodeVO {
    private String id;
    /**
     * host:port（脱敏，不含认证段）。
     */
    private String addr;
    /** MASTER / REPLICA。 */
    private String role;
    private List<String> flags;
    /** 槽位区间，如 "0-5460"。 */
    private List<String> slots;
    private boolean connected;
    private String masterId;
    private Long memoryUsageBytes;
    private String memoryUsageHuman;
    private Integer clients;
    private Long opsPerSec;
}
