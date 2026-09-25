package com.loadtest.service;

import com.loadtest.dto.TestConfigDto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 부하 테스트 "한 번의 실행"이 가지는 상태를 모은 객체.
 * <p>
 * 카운터와 에러 집계를 서비스 필드가 아니라 실행마다 새로 만드는 이 객체에 두어,
 * 동시에 돌아가는 실행끼리 결과가 섞이지 않도록 격리한다.
 * 모든 메서드는 여러 스레드에서 동시에 호출해도 안전하다.
 */
public final class TestRunContext {

    private final String testId;
    private final TestConfigDto config;
    private final int totalRequests;
    private final LocalDateTime startedAt = LocalDateTime.now();
    private final long startMillis = System.currentTimeMillis();

    private final AtomicInteger successCount = new AtomicInteger();
    private final AtomicInteger failCount = new AtomicInteger();
    private final AtomicInteger completedCount = new AtomicInteger();
    private final AtomicLong totalResponseTime = new AtomicLong();
    private final AtomicLong minResponseTime = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxResponseTime = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicInteger> errorBreakdown = new ConcurrentHashMap<>();

    public TestRunContext(String testId, TestConfigDto config) {
        this.testId = testId;
        this.config = config;
        this.totalRequests = config.getVirtualThreads() * config.getRequestsPerThread();
    }

    /** HTTP 응답을 받은 요청의 지연 시간을 기록하고 완료로 센다 (상태코드와 무관). */
    public void recordResponse(long elapsedMs) {
        totalResponseTime.addAndGet(elapsedMs);
        minResponseTime.accumulateAndGet(elapsedMs, Math::min);
        maxResponseTime.accumulateAndGet(elapsedMs, Math::max);
        completedCount.incrementAndGet();
    }

    public void recordSuccess() {
        successCount.incrementAndGet();
    }

    /** 응답은 받았지만 2xx가 아닌 요청 (지연은 {@link #recordResponse}로 따로 기록한다). */
    public void recordHttpFailure(String errorType) {
        failCount.incrementAndGet();
        countError(errorType);
    }

    /** 응답을 받지 못한 요청 (타임아웃, 연결 실패 등). 지연 통계에는 포함하지 않는다. */
    public void recordException(String errorType) {
        failCount.incrementAndGet();
        completedCount.incrementAndGet();
        countError(errorType);
    }

    private void countError(String errorType) {
        errorBreakdown.computeIfAbsent(errorType, k -> new AtomicInteger()).incrementAndGet();
    }

    public String testId() {
        return testId;
    }

    public TestConfigDto config() {
        return config;
    }

    public int totalRequests() {
        return totalRequests;
    }

    public LocalDateTime startedAt() {
        return startedAt;
    }

    public long startMillis() {
        return startMillis;
    }

    public int successCount() {
        return successCount.get();
    }

    public int failCount() {
        return failCount.get();
    }

    public int completedCount() {
        return completedCount.get();
    }

    public long totalResponseTimeMs() {
        return totalResponseTime.get();
    }

    /** 지연이 한 번도 기록되지 않았으면 0 */
    public long minResponseTimeMs() {
        long min = minResponseTime.get();
        return min == Long.MAX_VALUE ? 0 : min;
    }

    public long maxResponseTimeMs() {
        return maxResponseTime.get();
    }

    /** 에러 유형별 건수의 복사본 */
    public Map<String, Integer> errorBreakdown() {
        return errorBreakdown.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }
}
