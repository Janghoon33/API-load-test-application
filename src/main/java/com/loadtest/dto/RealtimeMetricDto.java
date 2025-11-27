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

    private String testId;  // 테스트 세션 ID

    private int totalRequests;  // 목표 총 요청 수

    private int completedRequests;  // 현재까지 완료된 요청 수

    private int successCount;

    private int failCount;

    private double progress;  // 진행률 (0-100)

    private double currentTps;

    private long avgResponseTimeMs;

    private long elapsedTimeMs;  // 경과 시간

    private long timestamp;

    private String status;  // RUNNING, COMPLETED, FAILED

    // 성공률 계산
    public double getSuccessRate() {
        return completedRequests > 0 ? (successCount * 100.0) / completedRequests : 0.0;
    }
}
