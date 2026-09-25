package com.loadtest.service;

import com.loadtest.dto.TestConfigDto;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RunExecutorFactoryTest {

    private final RunExecutorFactory factory = new RunExecutorFactory(2);

    @Test
    void 가상_스레드_타입은_가상_스레드에서_실행하고_testId가_들어간_이름을_붙인다() throws Exception {
        try (ExecutorService executor = factory.create(TestConfigDto.ThreadType.VIRTUAL, "test-abc")) {
            Future<Boolean> isVirtual = executor.submit(() -> Thread.currentThread().isVirtual());
            Future<String> name = executor.submit(() -> Thread.currentThread().getName());

            assertThat(isVirtual.get()).isTrue();
            assertThat(name.get()).startsWith("vu-test-abc-");
        }
    }

    @Test
    void 플랫폼_스레드_타입은_플랫폼_스레드에서_실행하고_testId가_들어간_이름을_붙인다() throws Exception {
        try (ExecutorService executor = factory.create(TestConfigDto.ThreadType.PLATFORM, "test-abc")) {
            Future<Boolean> isVirtual = executor.submit(() -> Thread.currentThread().isVirtual());
            Future<String> name = executor.submit(() -> Thread.currentThread().getName());

            assertThat(isVirtual.get()).isFalse();
            assertThat(name.get()).startsWith("pu-test-abc-");
        }
    }

    @Test
    void 플랫폼_스레드_풀은_설정한_크기를_넘지_않는다() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = factory.create(TestConfigDto.ThreadType.PLATFORM, "test-cap")) {
            for (int i = 0; i < 6; i++) {
                executor.submit(() -> {
                    int now = running.incrementAndGet();
                    maxRunning.accumulateAndGet(now, Math::max);
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    running.decrementAndGet();
                });
            }
            Thread.sleep(200); // 풀이 최대치까지 채워질 시간
            release.countDown();
        }

        assertThat(maxRunning.get()).isEqualTo(2);
    }

    @Test
    void close는_제출된_모든_작업의_완료를_기다린다() {
        AtomicInteger done = new AtomicInteger();

        try (ExecutorService executor = factory.create(TestConfigDto.ThreadType.VIRTUAL, "test-close")) {
            for (int i = 0; i < 100; i++) {
                executor.submit(() -> {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    done.incrementAndGet();
                });
            }
        }

        assertThat(done.get()).isEqualTo(100);
    }
}
