package com.example.redisadmin.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 集群/哨兵拓扑。节点级含内存与 QPS。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterTopologyVO {
    private Long profileId;
    private String name;
    private String mode;
    /**
     * 集群为 cluster_state；哨兵为 replication role。
     */
    private String state;
    private Integer slotsAssigned;
    private Integer slotsOk;
    private List<ClusterNodeVO> nodes;
}
