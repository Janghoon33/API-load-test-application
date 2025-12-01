package com.loadtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestResultDto {

    private Long executionId;

    private String threadType;

    private int totalRequests;

    private int successCount;

    private int failCount;

    private long avgResponseTimeMs;

    private long minResponseTimeMs;

    private long maxResponseTimeMs;

    private long totalDurationMs;

    private double tps; // Transactions Per Second

    private Map<String, Integer> errorBreakdown; // 에러 타입별 카운트

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    // 성공률 계산
    public double getSuccessRate() {
        return totalRequests > 0 ? (successCount * 100.0) / totalRequests : 0.0;
    }
}
