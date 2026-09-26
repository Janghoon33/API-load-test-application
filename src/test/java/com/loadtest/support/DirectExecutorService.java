package com.loadtest.support;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 제출된 작업을 호출한 스레드에서 즉시 동기 실행하는 executor.
 * <p>
 * 비동기로 시작하는 코드를 테스트할 때 이걸 주입하면 "작업이 언제 끝나는지"를 기다리거나
 * 타임아웃으로 추측할 필요 없이 결정적으로 검증할 수 있다.
 */
public class DirectExecutorService extends AbstractExecutorService {

    private volatile boolean shutdown;

    @Override
    public void execute(Runnable command) {
        command.run();
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        return List.of();
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
    }
}
