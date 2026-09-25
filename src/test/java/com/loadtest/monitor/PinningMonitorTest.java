package com.loadtest.monitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 계측 도구 자체를 검증한다.
 * <p>
 * "핀닝 0건"이라는 측정 결과가 의미를 가지려면, 이 모니터가 실제로 핀닝을 감지할 수 있다는 것이
 * 먼저 증명되어야 한다. 그래서 일부러 핀닝을 만들어 감지되는지 확인한다.
 */
class PinningMonitorTest {

    private static final Object LOCK = new Object();

    // JDK 24(JEP 491)부터는 synchronized 핀닝이 사라져 이 시나리오로는 이벤트가 발생하지 않는다.
    @Test
    @EnabledForJreRange(min = JRE.JAVA_21, max = JRE.JAVA_23)
    void synchronized_안에서_블로킹하는_가상_스레드의_핀닝을_감지한다() throws Exception {
        PinningMonitor monitor = new PinningMonitor(true);
        monitor.start();
        try {
            assertThat(monitor.isRunning()).isTrue();

            Thread virtualThread = Thread.ofVirtual().start(() -> {
                synchronized (LOCK) {
                    try {
                        Thread.sleep(100); // synchronized 안에서 블로킹 → 캐리어 스레드에 핀됨
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            virtualThread.join();

            // JFR 스트림은 배치로 전달되므로 도착을 기다린다
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(monitor.snapshot().count()).isGreaterThanOrEqualTo(1));

            PinningMonitor.Snapshot snapshot = monitor.snapshot();
            assertThat(snapshot.totalMillis()).isGreaterThan(0);
            assertThat(snapshot.stacks()).isNotEmpty();
        } finally {
            monitor.stop();
        }
    }

    @Test
    void 비활성화하면_스트림을_시작하지_않고_0건을_반환한다() {
        PinningMonitor monitor = new PinningMonitor(false);
        monitor.start();

        assertThat(monitor.isRunning()).isFalse();
        assertThat(monitor.snapshot().count()).isZero();
        monitor.stop();
    }

    @Test
    void 스냅샷_차이는_구간_값을_계산한다() {
        PinningMonitor.Snapshot earlier = new PinningMonitor.Snapshot(
                2, 10, java.util.Map.of("a", 2L));
        PinningMonitor.Snapshot later = new PinningMonitor.Snapshot(
                5, 30, java.util.Map.of("a", 3L, "b", 2L));

        PinningMonitor.Snapshot diff = later.minus(earlier);

        assertThat(diff.count()).isEqualTo(3);
        assertThat(diff.totalMillis()).isEqualTo(20);
        assertThat(diff.stacks()).containsEntry("a", 1L).containsEntry("b", 2L);
    }
}
