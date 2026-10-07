package com.loadtest.service;

import com.loadtest.dto.TestConfigDto;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

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

    /** 백분위 히스토그램이 기록하는 최대 지연(마이크로초). 요청 타임아웃(10초)보다 충분히 크다. */
    private static final long MAX_TRACKABLE_MICROS = 60_000_000L;
    private static final int SIGNIFICANT_DIGITS = 3;

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
    // 단위: 마이크로초. 여러 워커가 동시에 기록해도 안전하고, 읽는 쪽(샘플러)은 구간 단위로 비운다.
    private final Recorder latencyRecorder = new Recorder(MAX_TRACKABLE_MICROS, SIGNIFICANT_DIGITS);

    public TestRunContext(String testId, TestConfigDto config) {
        this.testId = testId;
        this.config = config;
        this.totalRequests = config.getVirtualThreads() * config.getRequestsPerThread();
    }

    /** HTTP 응답을 받은 요청의 지연 시간을 기록하고 완료로 센다 (상태코드와 무관). */
    public void recordResponse(long elapsedMs) {
        recordResponseNanos(elapsedMs * 1_000_000L);
    }

    /** {@link #recordResponse(long)}의 나노초 버전. 1ms 미만 지연도 백분위에서 구분된다. */
    public void recordResponseNanos(long elapsedNanos) {
        long elapsedMs = elapsedNanos / 1_000_000L;
        // HdrHistogram은 범위를 넘는 값을 예외로 거부하므로 상한으로 잘라서 기록한다
        long micros = Math.min(Math.max(elapsedNanos / 1_000L, 0L), MAX_TRACKABLE_MICROS);
        latencyRecorder.recordValue(micros);
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

    /**
     * 직전 호출 이후에 기록된 지연의 히스토그램(마이크로초)을 꺼내고 구간을 초기화한다.
     * 한 곳(샘플러)에서만 호출해야 구간이 서로 겹치거나 빠지지 않는다.
     */
    public Histogram drainInterval() {
        return latencyRecorder.getIntervalHistogram();
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
