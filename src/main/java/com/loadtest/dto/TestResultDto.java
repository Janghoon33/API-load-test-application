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

    // 응답 시간 백분위(ms). 백분위 도입 이전에 저장된 실행은 null
    private Double p50Ms;

    private Double p90Ms;

    private Double p95Ms;

    private Double p99Ms;

    private Double p999Ms;

    private Map<String, Integer> errorBreakdown; // 에러 타입별 카운트

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    // 성공률 계산
    public double getSuccessRate() {
        return totalRequests > 0 ? (successCount * 100.0) / totalRequests : 0.0;
    }
}
