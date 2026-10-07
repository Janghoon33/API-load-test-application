package com.loadtest.service;

import com.loadtest.dto.PercentilesDto;
import com.loadtest.dto.TestConfigDto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.within;

class TestRunContextTest {

    private TestRunContext newContext() {
        return new TestRunContext("test-ctx", TestConfigDto.builder()
                .url("http://localhost/")
                .threadType(TestConfigDto.ThreadType.VIRTUAL)
                .virtualThreads(5)
                .requestsPerThread(2)
                .build());
    }

    @Test
    void 총_요청_수는_스레드_수와_스레드당_요청_수의_곱이다() {
        assertThat(newContext().totalRequests()).isEqualTo(10);
    }

    @Test
    void 지연_시간의_합_최소_최대를_기록한다() {
        TestRunContext ctx = newContext();

        ctx.recordResponse(30);
        ctx.recordResponse(10);
        ctx.recordResponse(20);

        assertThat(ctx.totalResponseTimeMs()).isEqualTo(60);
        assertThat(ctx.minResponseTimeMs()).isEqualTo(10);
        assertThat(ctx.maxResponseTimeMs()).isEqualTo(30);
        assertThat(ctx.completedCount()).isEqualTo(3);
    }

    @Test
    void 기록한_지연으로_백분위를_계산한다() {
        TestRunContext ctx = newContext();
        for (int ms = 1; ms <= 100; ms++) {
            ctx.recordResponse(ms);
        }

        PercentilesDto p = PercentilesDto.from(ctx.drainInterval());

        // HdrHistogram 유효숫자 3자리 범위 안의 오차만 허용
        assertThat(p.p50()).isCloseTo(50.0, within(0.2));
        assertThat(p.p90()).isCloseTo(90.0, within(0.2));
        assertThat(p.p95()).isCloseTo(95.0, within(0.2));
        assertThat(p.p99()).isCloseTo(99.0, within(0.2));
        assertThat(p.p999()).isCloseTo(100.0, within(0.2));
    }

    @Test
    void 나노초_단위로_기록해도_1ms_미만_지연이_구분된다() {
        TestRunContext ctx = newContext();
        ctx.recordResponseNanos(250_000);

        PercentilesDto p = PercentilesDto.from(ctx.drainInterval());

        assertThat(p.p50()).isCloseTo(0.25, within(0.001));
    }

    @Test
    void 기록_범위를_넘는_지연도_예외_없이_최대값으로_기록된다() {
        TestRunContext ctx = newContext();

        ctx.recordResponse(10 * 60 * 1000);

        PercentilesDto p = PercentilesDto.from(ctx.drainInterval());
        assertThat(p.p99()).isCloseTo(60_000.0, within(100.0));
        assertThat(ctx.completedCount()).isEqualTo(1);
    }

    @Test
    void 응답이_없으면_백분위는_모두_0이다() {
        PercentilesDto p = PercentilesDto.from(newContext().drainInterval());

        assertThat(p).isEqualTo(new PercentilesDto(0, 0, 0, 0, 0));
    }

    @Test
    void 구간_히스토그램은_직전_drain_이후_기록분만_담는다() {
        TestRunContext ctx = newContext();
        ctx.recordResponse(10);
        ctx.recordResponse(20);
        assertThat(ctx.drainInterval().getTotalCount()).isEqualTo(2);

        ctx.recordResponse(30);

        assertThat(ctx.drainInterval().getTotalCount()).isEqualTo(1);
        assertThat(ctx.drainInterval().getTotalCount()).isZero();
    }

    @Test
    void 지연이_기록되지_않았으면_최소값은_0이다() {
        assertThat(newContext().minResponseTimeMs()).isZero();
    }

    @Test
    void HTTP_실패는_지연은_따로_기록하고_실패와_에러_유형을_센다() {
        TestRunContext ctx = newContext();

        ctx.recordResponse(5);
        ctx.recordHttpFailure("SERVER_ERROR_500");

        assertThat(ctx.failCount()).isEqualTo(1);
        assertThat(ctx.completedCount()).isEqualTo(1);
        assertThat(ctx.errorBreakdown()).containsExactly(entry("SERVER_ERROR_500", 1));
    }

    @Test
    void 예외는_완료로_세지만_지연_통계에는_포함하지_않는다() {
        TestRunContext ctx = newContext();

        ctx.recordException("TIMEOUT");

        assertThat(ctx.failCount()).isEqualTo(1);
        assertThat(ctx.completedCount()).isEqualTo(1);
        assertThat(ctx.totalResponseTimeMs()).isZero();
        assertThat(ctx.errorBreakdown()).containsExactly(entry("TIMEOUT", 1));
    }

    @Test
    void 서로_다른_컨텍스트의_상태는_섞이지_않는다() {
        TestRunContext a = newContext();
        TestRunContext b = newContext();

        a.recordHttpFailure("SERVER_ERROR_500");

        assertThat(b.failCount()).isZero();
        assertThat(b.errorBreakdown()).isEmpty();
    }

    @Test
    void 여러_스레드에서_동시에_기록해도_집계가_정확하다() {
        TestRunContext ctx = newContext();
        int threads = 50;
        int perThread = 200;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(CompletableFuture.runAsync(() -> {
                    for (int i = 0; i < perThread; i++) {
                        ctx.recordResponse(i);
                        ctx.recordHttpFailure("SERVER_ERROR_503");
                    }
                }, executor));
            }
            futures.forEach(CompletableFuture::join);
        }

        assertThat(ctx.completedCount()).isEqualTo(threads * perThread);
        assertThat(ctx.failCount()).isEqualTo(threads * perThread);
        assertThat(ctx.errorBreakdown()).containsExactly(entry("SERVER_ERROR_503", threads * perThread));
        assertThat(ctx.minResponseTimeMs()).isZero();
        assertThat(ctx.maxResponseTimeMs()).isEqualTo(perThread - 1);
    }
}
