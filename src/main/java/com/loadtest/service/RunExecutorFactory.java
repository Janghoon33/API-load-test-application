package com.loadtest.service;

import com.loadtest.dto.TestConfigDto;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 부하 테스트 "한 번의 실행"마다 사용할 워커 executor를 만든다.
 * <p>
 * executor를 싱글톤 빈으로 공유하지 않고 실행마다 새로 만들면, 실행 종료 시 {@link ExecutorService#close()}로
 * 모든 워커의 완료를 보장할 수 있고 실행 간에 스레드가 섞이지 않는다. 스레드 이름에 testId를 넣어
 * 스레드 덤프·JFR에서 어느 실행의 워커인지 식별할 수 있다.
 */
@Component
public class RunExecutorFactory {

    /**
     * @param poolSize PLATFORM 풀의 크기. {@link PlatformThreadBudget}이 이 실행에 허용한 플랫폼 스레드 수여야 하며,
     *                 그래야 동시에 겹치는 실행들의 합계가 프로세스 전체 상한을 넘지 않는다. VIRTUAL에서는 무시한다.
     */
    public ExecutorService create(TestConfigDto.ThreadType threadType, String testId, int poolSize) {
        return switch (threadType) {
            case VIRTUAL -> Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("vu-" + testId + "-", 0).factory());
            // 실행마다 풀을 만들지만 크기는 전역 예산이 정해 준 값이라 실행들의 합계는 상한을 넘지 않는다.
            // 가상 스레드와의 공정한 비교 방식은 Phase 3에서 재설계.
            case PLATFORM -> Executors.newFixedThreadPool(
                    Math.max(1, poolSize), Thread.ofPlatform().name("pu-" + testId + "-", 0).factory());
        };
    }
}
