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

    private static void pinOnce() throws InterruptedException {
        Thread virtualThread = Thread.ofVirtual().start(() -> {
            synchronized (LOCK) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        virtualThread.join();
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_21, max = JRE.JAVA_23)
    void awaitDelivery는_직전에_발생한_핀닝이_스냅샷에_반영된_뒤_돌아온다() throws Exception {
        PinningMonitor monitor = new PinningMonitor(true);
        monitor.start();
        try {
            // 폴링 없이 awaitDelivery만으로 도착이 확정되는지 3번 반복해서 본다 (실행 종료 시 최종 결과를 만드는 상황과 같다)
            for (int trial = 1; trial <= 3; trial++) {
                long before = monitor.snapshot().count();

                pinOnce();
                boolean settled = monitor.awaitDelivery(Duration.ofSeconds(5));

                assertThat(settled).as("시도 %d: 제한 시간 안에 확정되어야 한다", trial).isTrue();
                assertThat(monitor.snapshot().count())
                        .as("시도 %d: awaitDelivery가 돌아온 시점에 방금의 핀닝이 이미 집계되어 있어야 한다", trial)
                        .isGreaterThan(before);
            }
        } finally {
            monitor.stop();
        }
    }

    @Test
    void 이벤트가_없어도_awaitDelivery는_제한_시간_안에_돌아온다() throws Exception {
        PinningMonitor monitor = new PinningMonitor(true);
        monitor.start();
        try {
            long startNanos = System.nanoTime();

            boolean settled = monitor.awaitDelivery(Duration.ofSeconds(6));

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            assertThat(settled).as("이벤트가 없어도 플러시는 주기적으로 일어나야 한다").isTrue();
            assertThat(elapsedMs).as("플러시 몇 회 분량만 기다린다").isLessThan(5_000);
        } finally {
            monitor.stop();
        }
    }

    @Test
    void 꺼진_모니터의_awaitDelivery는_기다리지_않고_즉시_돌아온다() {
        PinningMonitor monitor = new PinningMonitor(false);
        monitor.start();

        long startNanos = System.nanoTime();
        boolean settled = monitor.awaitDelivery(Duration.ofSeconds(5));

        assertThat(settled).as("기다릴 것이 없으므로 확정으로 본다").isTrue();
        assertThat((System.nanoTime() - startNanos) / 1_000_000).isLessThan(200);
    }

    @Test
    void 제한_시간이_0이면_기다리지_않고_확정되지_않았다고_알린다() {
        PinningMonitor monitor = new PinningMonitor(true);
        monitor.start();
        try {
            long startNanos = System.nanoTime();

            assertThat(monitor.awaitDelivery(Duration.ZERO)).isFalse();
            assertThat((System.nanoTime() - startNanos) / 1_000_000).isLessThan(200);
        } finally {
            monitor.stop();
        }
    }

    @Test
    void awaitDelivery는_인터럽트되면_플래그를_복원하고_돌아온다() {
        PinningMonitor monitor = new PinningMonitor(true);
        monitor.start();
        try {
            Thread.currentThread().interrupt();

            boolean settled = monitor.awaitDelivery(Duration.ofSeconds(5));

            assertThat(settled).isFalse();
            assertThat(Thread.interrupted()).as("인터럽트 플래그가 복원되어야 상위에서 중단을 알 수 있다").isTrue();
        } finally {
            monitor.stop();
        }
    }
}
