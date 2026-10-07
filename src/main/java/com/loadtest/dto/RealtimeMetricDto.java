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

    private double currentTps;  // 직전 샘플 이후 구간의 순간 TPS

    private long avgResponseTimeMs;  // 직전 샘플 이후 구간의 평균 응답 시간

    private double p95Ms;  // 시작부터의 누적 백분위

    private double p99Ms;

    private long heapUsedBytes;

    private int platformThreadCount;  // 플랫폼 스레드만. 가상 스레드는 포함되지 않는다

    private int activeWorkers;  // 이 실행에서 아직 끝나지 않은 워커 수

    private int carrierParallelism;  // 가상 스레드 캐리어 풀 크기

    private long pinnedCount;  // 실행 시작 이후 핀닝 수 (JVM 전역 증가분). 실시간 값은 최대 약 1초 늦고, 완료 시점의 값은 전달 확정 후의 값

    private long elapsedTimeMs;  // 경과 시간

    private long timestamp;

    private String status;  // RUNNING, COMPLETED, FAILED

    // 성공률 계산
    public double getSuccessRate() {
        return completedRequests > 0 ? (successCount * 100.0) / completedRequests : 0.0;
    }
}
