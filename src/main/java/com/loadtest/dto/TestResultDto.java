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

    // 런타임 지표. 도입 이전에 저장된 실행은 null
    private Long peakHeapBytes;

    private Integer peakPlatformThreads; // 플랫폼 스레드만 (가상 스레드 제외)

    private Long gcCount; // 실행 중 발생한 횟수 (JVM 전역 증가분)

    private Long gcTimeMs;

    private Long pinnedCount; // 실행 중 핀닝 수 (JVM 전역 증가분, 최대 약 1초 지연)

    private Long pinnedTimeMs;

    private Map<String, Integer> errorBreakdown; // 에러 타입별 카운트

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    // 성공률 계산
    public double getSuccessRate() {
        return totalRequests > 0 ? (successCount * 100.0) / totalRequests : 0.0;
    }
}
