package com.loadtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RealtimeMetricDto {

    private int successCount;

    private int failCount;

    private double currentTps;

    private long avgResponseTimeMs;

    private long timestamp;
}
