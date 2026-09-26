package com.loadtest.service;

import com.loadtest.dto.TestConfigDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * PLATFORM 스레드 타입으로 실행되는 모든 부하 테스트가 <b>함께</b> 사용할 수 있는 플랫폼 스레드 총량(프로세스 전체 상한).
 * <p>
 * 실행마다 별도의 스레드 풀을 만들면 동시에 겹치는 실행 수에 비례해 스레드가 늘어난다
 * (상한 500일 때 실행 10개면 최대 5,000개). 그래서 실행별 executor는 유지하되, 실행이 시작되기 전에
 * 여기서 permit을 획득하게 해 합계가 상한을 넘지 못하게 한다. permit이 부족하면 다른 실행이 끝나
 * 반환할 때까지 대기한다.
 * <p>
 * 가상 스레드 실행은 예산을 사용하지 않는다.
 */
@Slf4j
@Component
public class PlatformThreadBudget {

    private final int max;
    /** 공정 모드: 많은 permit이 필요한 실행이 작은 실행들에 계속 밀리지 않게 한다. */
    private final Semaphore permits;

    public PlatformThreadBudget(@Value("${loadtest.max-platform-threads:500}") int max) {
        if (max < 1) {
            throw new IllegalArgumentException("loadtest.max-platform-threads는 1 이상이어야 합니다: " + max);
        }
        this.max = max;
        this.permits = new Semaphore(max, true);
    }

    /**
     * 이 실행에 필요한 만큼의 플랫폼 스레드 permit을 획득한다. 부족하면 대기한다.
     *
     * @return 획득한 permit 수. 가상 스레드 실행이면 0(대기하지 않음). 반드시 {@link #release(int)}로 반환해야 한다.
     */
    public int acquireFor(TestConfigDto config) {
        if (config.getThreadType() != TestConfigDto.ThreadType.PLATFORM) {
            return 0;
        }
        // 한 실행이 상한보다 많이 요구해도 상한까지만 사용한다(고정 크기 풀이 그 이상 만들지 못하므로).
        // 이렇게 해야 어떤 실행도 영구히 대기하지 않는다.
        int needed = Math.min(config.getVirtualThreads(), max);
        if (needed <= 0) {
            return 0;
        }
        try {
            if (permits.availablePermits() < needed) {
                log.info("플랫폼 스레드 예산 대기 - 필요 {}, 사용 가능 {}, 전체 {}",
                        needed, permits.availablePermits(), max);
            }
            permits.acquire(needed);
            return needed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("플랫폼 스레드 예산을 기다리는 중 인터럽트되었습니다", e);
        }
    }

    public void release(int acquired) {
        if (acquired > 0) {
            permits.release(acquired);
        }
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    public int maxPermits() {
        return max;
    }
}
