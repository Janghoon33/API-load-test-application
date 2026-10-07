package com.loadtest.service;

import com.loadtest.dto.PercentilesDto;
import com.loadtest.dto.TestConfigDto;
import com.loadtest.monitor.JvmMetricsProbe.Reading;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 시각을 직접 주입해 순간 TPS·구간 평균·누적 백분위 계산을 결정적으로 검증한다.
 */
class RunMetricSamplerTest {

    private static final long START = 5_000_000_000L; // 임의의 nanoTime 기준점
    private static final long SECOND = 1_000_000_000L;

    private final TestRunContext ctx = new TestRunContext("test-sampler", TestConfigDto.builder()
            .url("http://localhost/")
            .threadType(TestConfigDto.ThreadType.VIRTUAL)
            .virtualThreads(10)
            .requestsPerThread(100)
            .build());
    /** JVM 전역 누적값을 테스트가 직접 조작한다 */
    private final AtomicReference<Reading> jvm = new AtomicReference<>(reading(100, 10, 0, 0, 0, 0));
    private final RunMetricSampler sampler = new RunMetricSampler(ctx, START, jvm::get);

    private static Reading reading(long heap, int threads, long gcCount, long gcTimeMs, long pinned, long pinnedMs) {
        return new Reading(heap, threads, gcCount, gcTimeMs, 4, pinned, pinnedMs);
    }

    private void respond(int count, long ms) {
        for (int i = 0; i < count; i++) {
            ctx.recordResponse(ms);
            ctx.recordSuccess();
        }
    }

    @Test
    void 순간_TPS는_직전_샘플과의_차분으로_계산한다() {
        respond(100, 10);
        MetricSample first = sampler.sample(START + SECOND);

        // 두 번째 구간에는 한 건도 없었다. 누적 평균(100/2s=50)이 아니라 0이어야 순간값이다.
        MetricSample second = sampler.sample(START + 2 * SECOND);

        assertThat(first.instantTps()).isCloseTo(100.0, within(0.001));
        assertThat(second.instantTps()).isCloseTo(0.0, within(0.001));
        assertThat(second.completed()).as("누적 완료 건수는 유지된다").isEqualTo(100);
    }

    @Test
    void 구간이_1초가_아니어도_경과_시간으로_나눠_계산한다() {
        respond(50, 10);

        MetricSample sample = sampler.sample(START + SECOND / 2);

        assertThat(sample.instantTps()).isCloseTo(100.0, within(0.001));
    }

    @Test
    void 구간_평균_응답시간은_그_구간의_실제_값이다() {
        respond(10, 10);
        MetricSample first = sampler.sample(START + SECOND);
        respond(10, 30);
        MetricSample second = sampler.sample(START + 2 * SECOND);
        MetricSample empty = sampler.sample(START + 3 * SECOND);

        // B3: 예전에는 항상 0으로 전송됐다
        assertThat(first.intervalAvgMs()).isCloseTo(10.0, within(0.1));
        assertThat(second.intervalAvgMs()).isCloseTo(30.0, within(0.1));
        assertThat(empty.intervalAvgMs()).as("구간에 응답이 없으면 0").isZero();
    }

    @Test
    void 누적_백분위는_모든_구간을_합친_분포다() {
        for (int ms = 1; ms <= 50; ms++) {
            ctx.recordResponse(ms);
        }
        sampler.sample(START + SECOND);
        for (int ms = 51; ms <= 100; ms++) {
            ctx.recordResponse(ms);
        }

        PercentilesDto cumulative = sampler.sample(START + 2 * SECOND).cumulative();

        assertThat(cumulative.p50()).isCloseTo(50.0, within(0.2));
        assertThat(cumulative.p99()).isCloseTo(99.0, within(0.2));
    }

    @Test
    void 마지막_샘플_이후에_기록된_응답도_finish에_반영된다() {
        for (int ms = 1; ms <= 100; ms++) {
            ctx.recordResponse(ms);
        }

        PercentilesDto summary = sampler.finish(START + SECOND / 10).cumulative();

        assertThat(summary.p95()).isCloseTo(95.0, within(0.2));
        assertThat(summary.p999()).isCloseTo(100.0, within(0.2));
    }

    @Test
    void 샘플에는_진행_수치와_경과_시간이_담긴다() {
        respond(3, 5);
        ctx.recordException("TIMEOUT");

        MetricSample sample = sampler.sample(START + 2 * SECOND);

        assertThat(sample.completed()).isEqualTo(4);
        assertThat(sample.success()).isEqualTo(3);
        assertThat(sample.fail()).isEqualTo(1);
        assertThat(sample.elapsedMs()).isEqualTo(2000);
    }

    @Test
    void GC와_핀닝은_실행_시작_시점_대비_증가분이다() {
        // 샘플러를 만든 시점(기준선)에 JVM은 이미 GC 7회·핀닝 3회를 겪은 상태였다 → 이 실행의 몫이 아니다
        AtomicReference<Reading> before = new AtomicReference<>(reading(100, 10, 7, 70, 3, 30));
        RunMetricSampler late = new RunMetricSampler(ctx, START, before::get);

        before.set(reading(100, 10, 9, 95, 5, 50));
        RuntimeSnapshot runtime = late.sample(START + SECOND).runtime();

        assertThat(runtime.gcCount()).isEqualTo(2);
        assertThat(runtime.gcTimeMs()).isEqualTo(25);
        assertThat(runtime.pinnedCount()).isEqualTo(2);
        assertThat(runtime.pinnedTimeMs()).isEqualTo(20);
    }

    @Test
    void 피크_힙과_피크_플랫폼_스레드는_샘플_중_최댓값이다() {
        jvm.set(reading(500, 20, 0, 0, 0, 0));
        sampler.sample(START + SECOND);
        jvm.set(reading(900, 80, 0, 0, 0, 0));
        sampler.sample(START + 2 * SECOND);
        jvm.set(reading(300, 15, 0, 0, 0, 0));
        RuntimeSnapshot last = sampler.sample(START + 3 * SECOND).runtime();

        assertThat(last.heapUsedBytes()).as("현재값은 마지막 샘플").isEqualTo(300);
        assertThat(last.platformThreads()).isEqualTo(15);
        assertThat(last.peakHeapBytes()).isEqualTo(900);
        assertThat(last.peakPlatformThreads()).isEqualTo(80);
    }

    @Test
    void 기준선_시점의_값도_피크에_포함된다() {
        jvm.set(reading(100, 10, 0, 0, 0, 0));
        RunMetricSampler fresh = new RunMetricSampler(ctx, START, jvm::get);
        jvm.set(reading(50, 5, 0, 0, 0, 0));

        RuntimeSnapshot runtime = fresh.sample(START + SECOND).runtime();

        assertThat(runtime.peakHeapBytes()).isEqualTo(100);
        assertThat(runtime.peakPlatformThreads()).isEqualTo(10);
    }

    @Test
    void 활성_워커_수와_캐리어_풀_크기가_샘플에_담긴다() {
        ctx.workerStarted();
        ctx.workerStarted();
        ctx.workerStarted();
        ctx.workerFinished();

        RuntimeSnapshot runtime = sampler.sample(START + SECOND).runtime();

        assertThat(runtime.activeWorkers()).isEqualTo(2);
        assertThat(runtime.carrierParallelism()).isEqualTo(4);
    }

    @Test
    void observePeak는_피크만_갱신하고_이후_샘플에서도_유지된다() {
        jvm.set(reading(900, 80, 0, 0, 0, 0));
        sampler.observePeak();
        jvm.set(reading(300, 15, 0, 0, 0, 0));

        RuntimeSnapshot runtime = sampler.sample(START + SECOND).runtime();

        assertThat(runtime.heapUsedBytes()).as("현재값은 샘플 시점 값").isEqualTo(300);
        assertThat(runtime.peakHeapBytes()).isEqualTo(900);
        assertThat(runtime.peakPlatformThreads()).isEqualTo(80);
    }

    @Test
    void observePeak는_구간_상태를_소비하지_않는다() {
        respond(100, 10);

        sampler.observePeak();
        MetricSample sample = sampler.sample(START + SECOND);

        // observePeak가 구간 히스토그램을 비우거나 직전 샘플 시각을 옮겼다면 아래 값이 깨진다
        assertThat(sample.instantTps()).isCloseTo(100.0, within(0.001));
        assertThat(sample.intervalAvgMs()).isCloseTo(10.0, within(0.1));
    }
}
