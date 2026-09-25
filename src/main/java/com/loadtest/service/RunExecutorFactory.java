package com.loadtest.service;

import com.loadtest.dto.TestConfigDto;
import org.springframework.beans.factory.annotation.Value;
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

    private final int maxPlatformThreads;

    public RunExecutorFactory(@Value("${loadtest.max-platform-threads:500}") int maxPlatformThreads) {
        this.maxPlatformThreads = maxPlatformThreads;
    }

    public ExecutorService create(TestConfigDto.ThreadType threadType, String testId) {
        return switch (threadType) {
            case VIRTUAL -> Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("vu-" + testId + "-", 0).factory());
            // 플랫폼 스레드는 현행 의미(고정 크기 풀)를 유지한다. 가상 스레드와의 공정한 비교 방식은 Phase 3에서 재설계.
            case PLATFORM -> Executors.newFixedThreadPool(
                    maxPlatformThreads, Thread.ofPlatform().name("pu-" + testId + "-", 0).factory());
        };
    }
}
