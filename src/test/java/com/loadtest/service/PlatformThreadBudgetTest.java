package com.loadtest.service;

import com.loadtest.dto.TestConfigDto;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class PlatformThreadBudgetTest {

    private static TestConfigDto config(TestConfigDto.ThreadType type, int workers) {
        return TestConfigDto.builder()
                .url("http://localhost/")
                .threadType(type)
                .virtualThreads(workers)
                .requestsPerThread(1)
                .build();
    }

    @Test
    void 가상_스레드_실행은_permit을_사용하지_않고_대기하지_않는다() {
        PlatformThreadBudget budget = new PlatformThreadBudget(4);

        int acquired = budget.acquireFor(config(TestConfigDto.ThreadType.VIRTUAL, 50_000));

        assertThat(acquired).isZero();
        assertThat(budget.availablePermits()).isEqualTo(4);
    }

    @Test
    void 플랫폼_실행은_필요한_만큼의_permit을_획득하고_반환하면_원래대로_돌아온다() {
        PlatformThreadBudget budget = new PlatformThreadBudget(10);

        int acquired = budget.acquireFor(config(TestConfigDto.ThreadType.PLATFORM, 3));

        assertThat(acquired).isEqualTo(3);
        assertThat(budget.availablePermits()).isEqualTo(7);

        budget.release(acquired);
        assertThat(budget.availablePermits()).isEqualTo(10);
    }

    @Test
    void 상한보다_많은_스레드를_요구해도_상한까지만_획득한다() throws Exception {
        PlatformThreadBudget budget = new PlatformThreadBudget(4);

        // 클램프가 없으면 영구 대기하므로, 테스트가 멈추지 않도록 별도 스레드에서 시도하고 제한 시간 안에 끝나는지 본다
        AtomicInteger acquired = new AtomicInteger(-1);
        Thread t = Thread.ofVirtual().start(() ->
                acquired.set(budget.acquireFor(config(TestConfigDto.ThreadType.PLATFORM, 1_000))));
        t.join(3_000);

        assertThat(t.isAlive()).as("요구량이 상한을 넘어도 영구 대기하면 안 된다").isFalse();
        assertThat(acquired.get()).isEqualTo(4);
        assertThat(budget.availablePermits()).isZero();
        budget.release(acquired.get());
    }

    @Test
    void 예산이_부족하면_다른_실행이_반환할_때까지_대기한다() throws Exception {
        PlatformThreadBudget budget = new PlatformThreadBudget(4);
        int first = budget.acquireFor(config(TestConfigDto.ThreadType.PLATFORM, 3)); // 남은 permit 1

        AtomicBoolean secondAcquired = new AtomicBoolean(false);
        CountDownLatch finished = new CountDownLatch(1);
        Thread second = Thread.ofVirtual().start(() -> {
            int acquired = budget.acquireFor(config(TestConfigDto.ThreadType.PLATFORM, 3)); // 3 필요, 1 가능 → 대기
            secondAcquired.set(true);
            budget.release(acquired);
            finished.countDown();
        });

        Thread.sleep(300);
        assertThat(secondAcquired.get()).as("예산이 부족하면 획득하지 못하고 대기해야 한다").isFalse();

        budget.release(first);
        assertThat(finished.await(5, TimeUnit.SECONDS)).as("반환되면 대기하던 실행이 진행한다").isTrue();
        assertThat(secondAcquired.get()).isTrue();
        second.join();
        assertThat(budget.availablePermits()).isEqualTo(4);
    }

    @Test
    void 대기_중_인터럽트되면_예외를_던지고_permit을_소모하지_않는다() throws Exception {
        PlatformThreadBudget budget = new PlatformThreadBudget(2);
        int held = budget.acquireFor(config(TestConfigDto.ThreadType.PLATFORM, 2)); // 전부 사용 중

        AtomicInteger outcome = new AtomicInteger(); // 1=예외 발생
        AtomicBoolean interruptFlag = new AtomicBoolean();
        Thread waiter = Thread.ofPlatform().start(() -> {
            try {
                budget.acquireFor(config(TestConfigDto.ThreadType.PLATFORM, 1));
            } catch (IllegalStateException e) {
                outcome.set(1);
                interruptFlag.set(Thread.currentThread().isInterrupted());
            }
        });
        Thread.sleep(200);
        waiter.interrupt();
        waiter.join(5_000);

        assertThat(outcome.get()).isEqualTo(1);
        assertThat(interruptFlag.get()).as("인터럽트 플래그를 복원해야 한다").isTrue();
        budget.release(held);
        assertThat(budget.availablePermits()).as("대기하다 취소된 요청이 permit을 가져가면 안 된다").isEqualTo(2);
    }

    @Test
    void 상한이_1_미만이면_생성할_수_없다() {
        assertThatThrownBy(() -> new PlatformThreadBudget(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-platform-threads");
    }

    @Test
    void 큰_요구를_먼저_기다리던_실행이_작은_요구들에_계속_밀리지_않는다() throws Exception {
        // 공정 모드 확인: 큰 요구(3)가 대기 중일 때 뒤에 온 작은 요구(1)가 새치기하지 않는다
        PlatformThreadBudget budget = new PlatformThreadBudget(4);
        int held = budget.acquireFor(config(TestConfigDto.ThreadType.PLATFORM, 2)); // 남은 2

        AtomicBoolean bigDone = new AtomicBoolean();
        Thread big = Thread.ofVirtual().start(() -> {
            int a = budget.acquireFor(config(TestConfigDto.ThreadType.PLATFORM, 3)); // 3 필요 → 대기
            bigDone.set(true);
            budget.release(a);
        });
        await().atMost(Duration.ofSeconds(2)).until(() -> big.getState() == Thread.State.WAITING);

        AtomicBoolean smallDone = new AtomicBoolean();
        Thread small = Thread.ofVirtual().start(() -> {
            int a = budget.acquireFor(config(TestConfigDto.ThreadType.PLATFORM, 1)); // 1은 가능하지만 큰 요구 뒤에 서야 함
            smallDone.set(true);
            budget.release(a);
        });
        Thread.sleep(300);
        assertThat(smallDone.get()).as("공정 모드에서는 앞선 큰 요구를 앞지르지 않는다").isFalse();

        budget.release(held);
        big.join(5_000);
        small.join(5_000);
        assertThat(bigDone.get()).isTrue();
        assertThat(smallDone.get()).isTrue();
        assertThat(budget.availablePermits()).isEqualTo(4);
    }
}
