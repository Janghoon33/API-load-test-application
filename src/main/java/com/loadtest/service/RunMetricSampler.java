package com.loadtest.service;

import com.loadtest.dto.PercentilesDto;
import com.loadtest.monitor.JvmMetricsProbe.Reading;
import org.HdrHistogram.Histogram;

import java.util.function.Supplier;

/**
 * 실행 하나의 지표를 주기적으로 읽어 구간 값(순간 TPS, 구간 평균)과 누적 값(백분위)을 계산한다.
 * <p>
 * 워커는 요청만 수행하고 {@link TestRunContext}에 기록할 뿐이며, 읽기·계산·전송은 이 샘플러를 부르는
 * 스케줄러 스레드 하나가 맡는다. 시각을 인자로 받으므로 단위 테스트에서 시간을 직접 주입할 수 있다.
 * 구간 히스토그램을 비우는 {@link TestRunContext#drainInterval()}은 이 클래스에서만 호출한다.
 */
public final class RunMetricSampler {

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    private static final double MICROS_PER_MS = 1000.0;

    private final TestRunContext ctx;
    private final long startNanos;
    // 모든 구간을 더한 누적 분포. TestRunContext의 기록 범위와 같은 단위(마이크로초)·정밀도를 쓴다.
    private final Histogram cumulative = new Histogram(60_000_000L, 3);

    private final Supplier<Reading> jvmReader;
    // 실행 시작 시점의 JVM 전역 누적값. 이후 값과의 차이가 "이 실행의 몫"이다.
    private final Reading baseline;

    private long previousNanos;
    private int previousCompleted;
    private long peakHeapBytes;
    private int peakPlatformThreads;

    public RunMetricSampler(TestRunContext ctx, long startNanos, Supplier<Reading> jvmReader) {
        this.ctx = ctx;
        this.startNanos = startNanos;
        this.previousNanos = startNanos;
        this.jvmReader = jvmReader;
        this.baseline = jvmReader.get();
        this.peakHeapBytes = baseline.heapUsedBytes();
        this.peakPlatformThreads = baseline.platformThreads();
    }

    /** 직전 샘플 이후의 구간을 마감하고 현재 값을 계산한다. */
    public synchronized MetricSample sample(long nowNanos) {
        Histogram interval = ctx.drainInterval();
        cumulative.add(interval);

        int completed = ctx.completedCount();
        long deltaNanos = nowNanos - previousNanos;
        double instantTps = deltaNanos > 0
                ? (completed - previousCompleted) * NANOS_PER_SECOND / deltaNanos
                : 0.0;
        double intervalAvgMs = interval.getTotalCount() > 0 ? interval.getMean() / MICROS_PER_MS : 0.0;

        previousNanos = nowNanos;
        previousCompleted = completed;

        Reading jvm = jvmReader.get();
        // ThreadMXBean.resetPeakThreadCount()는 JVM 전역 상태를 바꾸므로 쓰지 않고, 샘플 값의 최댓값을 직접 추적한다
        peakHeapBytes = Math.max(peakHeapBytes, jvm.heapUsedBytes());
        peakPlatformThreads = Math.max(peakPlatformThreads, jvm.platformThreads());
        RuntimeSnapshot runtime = new RuntimeSnapshot(
                jvm.heapUsedBytes(),
                jvm.platformThreads(),
                ctx.activeWorkers(),
                jvm.carrierParallelism(),
                peakHeapBytes,
                peakPlatformThreads,
                jvm.gcCount() - baseline.gcCount(),
                jvm.gcTimeMs() - baseline.gcTimeMs(),
                jvm.pinnedCount() - baseline.pinnedCount(),
                jvm.pinnedTimeMs() - baseline.pinnedTimeMs());

        return new MetricSample(
                completed,
                ctx.successCount(),
                ctx.failCount(),
                instantTps,
                intervalAvgMs,
                PercentilesDto.from(cumulative),
                (nowNanos - startNanos) / 1_000_000L,
                runtime);
    }

    /** 마지막 구간까지 반영한 최종 값. 모든 워커가 끝난 뒤에 호출한다. */
    public MetricSample finish(long nowNanos) {
        return sample(nowNanos);
    }
}
