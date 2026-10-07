package com.loadtest.monitor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JvmMetricsProbeTest {

    private final JvmMetricsProbe probe = new JvmMetricsProbe(new PinningMonitor(false));

    @Test
    void 힙_사용량과_플랫폼_스레드_수는_양수다() {
        JvmMetricsProbe.Reading reading = probe.read();

        assertThat(reading.heapUsedBytes()).isPositive();
        assertThat(reading.platformThreads()).isPositive();
    }

    @Test
    void 가상_스레드는_플랫폼_스레드_수에_포함되지_않는다() throws Exception {
        int before = probe.read().platformThreads();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.List<Thread> virtuals = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            virtuals.add(Thread.ofVirtual().start(() -> {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                }
            }));
        }

        int during = probe.read().platformThreads();
        release.countDown();
        for (Thread t : virtuals) {
            t.join();
        }

        // 가상 스레드 200개가 떠 있어도 플랫폼 스레드 수는 (캐리어 몇 개 외에는) 늘지 않는다
        assertThat(during - before).as("ThreadMXBean은 가상 스레드를 세지 않는다").isLessThan(50);
    }

    @Test
    void GC_누적값은_음수가_아니고_감소하지_않는다() {
        JvmMetricsProbe.Reading first = probe.read();
        System.gc();
        JvmMetricsProbe.Reading second = probe.read();

        assertThat(first.gcCount()).isGreaterThanOrEqualTo(0);
        assertThat(first.gcTimeMs()).isGreaterThanOrEqualTo(0);
        assertThat(second.gcCount()).isGreaterThanOrEqualTo(first.gcCount());
        assertThat(second.gcTimeMs()).isGreaterThanOrEqualTo(first.gcTimeMs());
    }

    @Test
    void 핀닝_모니터가_꺼져_있으면_핀닝_수치는_0이다() {
        JvmMetricsProbe.Reading reading = probe.read();

        assertThat(reading.pinnedCount()).isZero();
        assertThat(reading.pinnedTimeMs()).isZero();
    }

    @Test
    void 캐리어_풀_크기는_시스템_속성이_있으면_그_값이다() {
        String key = "jdk.virtualThreadScheduler.parallelism";
        String original = System.getProperty(key);
        try {
            System.setProperty(key, "3");
            assertThat(probe.read().carrierParallelism()).isEqualTo(3);

            System.clearProperty(key);
            assertThat(probe.read().carrierParallelism()).isEqualTo(Runtime.getRuntime().availableProcessors());

            System.setProperty(key, "not-a-number");
            assertThat(probe.read().carrierParallelism())
                    .as("잘못된 값이면 기본값으로 대체")
                    .isEqualTo(Runtime.getRuntime().availableProcessors());
        } finally {
            if (original == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, original);
            }
        }
    }
}
