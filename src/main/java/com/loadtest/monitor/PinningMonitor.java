package com.loadtest.monitor;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * 가상 스레드 핀닝(pinning)을 JFR 이벤트({@code jdk.VirtualThreadPinned})로 계측한다.
 * <p>
 * 핀닝은 가상 스레드가 캐리어(플랫폼) 스레드에 묶인 채 블로킹되는 현상으로, Java 21에서는
 * {@code synchronized} 블록 안에서 블로킹할 때 발생한다. "핀닝이 있을 것"이라고 가정하지 않고
 * 실제로 발생하는지, 발생한다면 <b>어느 코드 경로</b>에서 나는지를 측정하기 위한 도구다.
 * <p>
 * 한계: JFR 스트림은 이벤트를 배치로(최대 약 1초 지연) 전달하므로 {@link #snapshot()}은
 * 직전 1초 이내의 이벤트를 아직 반영하지 못할 수 있다. 실시간 표시는 그만큼 늦지만, 실행이 끝나 최종 결과를
 * 만들 때는 {@link #awaitDelivery(Duration)}로 전달을 확정한 뒤 스냅샷을 읽는다. 확정하지 않으면 마지막
 * 구간의 핀닝이 최종 결과에서 영구히 누락되고, 다음 실행의 기준선 뒤에 늦게 도착해 잘못 귀속될 수도 있다.
 * 제한 시간 안에 확정되지 못하면 일부가 누락될 수 있다. 핀닝 수치는 JVM 전역이라 동시에 도는 실행끼리는 섞인다.
 */
@Slf4j
@Component
public class PinningMonitor {

    /** 서로 다른 스택 키를 추적하는 최대 개수 (메모리 상한) */
    private static final int MAX_TRACKED_STACKS = 50;
    /** 스택 키에 사용하는 프레임 수 */
    private static final int STACK_KEY_DEPTH = 6;

    /**
     * 호출 시점에 이미 진행 중이던 플러시는 호출 이전 데이터만 반영했을 수 있으므로, 한 번 더 기다려
     * "호출 이후에 시작된 플러시가 끝났다"를 보장한다.
     */
    private static final int FLUSHES_TO_SETTLE = 2;

    private final boolean enabled;
    private final Duration settleTimeout;
    private final Object flushLock = new Object();
    private long flushCount; // flushLock으로 보호
    private final LongAdder eventCount = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private final ConcurrentHashMap<String, LongAdder> stackCounts = new ConcurrentHashMap<>();

    private volatile RecordingStream stream;

    @Autowired
    public PinningMonitor(
            @Value("${loadtest.pinning-monitor.enabled:true}") boolean enabled,
            @Value("${loadtest.pinning-monitor.settle-timeout:2s}") Duration settleTimeout) {
        this.enabled = enabled;
        this.settleTimeout = settleTimeout;
    }

    public PinningMonitor(boolean enabled) {
        this(enabled, Duration.ofSeconds(2));
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("PinningMonitor 비활성화됨 (loadtest.pinning-monitor.enabled=false)");
            return;
        }
        try {
            RecordingStream recording = new RecordingStream();
            recording.enable("jdk.VirtualThreadPinned")
                    .withThreshold(Duration.ofMillis(1))
                    .withStackTrace();
            recording.onEvent("jdk.VirtualThreadPinned", this::record);
            recording.onFlush(this::onFlush);
            recording.startAsync();
            this.stream = recording;
            log.info("PinningMonitor 시작 (jdk.VirtualThreadPinned, threshold=1ms)");
        } catch (Throwable t) {
            // JFR을 쓸 수 없는 JVM에서도 애플리케이션은 정상 기동해야 한다
            log.warn("PinningMonitor를 시작하지 못했습니다. 핀닝 계측 없이 계속합니다: {}", t.toString());
        }
    }

    @PreDestroy
    public void stop() {
        RecordingStream recording = this.stream;
        if (recording != null) {
            recording.close();
            this.stream = null;
        }
    }

    private void onFlush() {
        synchronized (flushLock) {
            flushCount++;
            flushLock.notifyAll();
        }
    }

    /** 설정된 제한 시간({@code loadtest.pinning-monitor.settle-timeout})만큼 {@link #awaitDelivery(Duration)}한다. */
    public boolean awaitDelivery() {
        return awaitDelivery(settleTimeout);
    }

    /**
     * 이 호출 이전에 발생한 핀닝 이벤트가 {@link #snapshot()}에 반영될 때까지 기다린다.
     * 호출 이후에 시작된 JFR 플러시가 끝나면 그 이전에 커밋된 이벤트는 이미 전달되었다고 본다.
     *
     * @return 기다릴 것이 없거나(모니터가 꺼짐) 전달이 확정되면 true,
     *         제한 시간이 0이거나 초과했거나 인터럽트되면 false (인터럽트 플래그는 복원한다)
     */
    public boolean awaitDelivery(Duration timeout) {
        if (stream == null) {
            return true;
        }
        if (timeout.isZero() || timeout.isNegative()) {
            return false;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (flushLock) {
            long target = flushCount + FLUSHES_TO_SETTLE;
            while (flushCount < target) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    log.warn("핀닝 이벤트 전달을 {}ms 안에 확정하지 못했습니다. 마지막 구간의 핀닝이 누락될 수 있습니다.",
                            timeout.toMillis());
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(flushLock, remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    private void record(RecordedEvent event) {
        eventCount.increment();
        totalNanos.add(event.getDuration().toNanos());

        String key = stackKey(event);
        LongAdder counter = stackCounts.get(key);
        if (counter == null && stackCounts.size() < MAX_TRACKED_STACKS) {
            counter = stackCounts.computeIfAbsent(key, k -> new LongAdder());
        }
        if (counter != null) {
            counter.increment();
        }
    }

    private String stackKey(RecordedEvent event) {
        if (event.getStackTrace() == null) {
            return "(스택 없음)";
        }
        List<RecordedFrame> frames = event.getStackTrace().getFrames();
        return frames.stream()
                .limit(STACK_KEY_DEPTH)
                .map(f -> f.getMethod().getType().getName() + "." + f.getMethod().getName())
                .collect(Collectors.joining(" <- "));
    }

    public boolean isRunning() {
        return stream != null;
    }

    public Snapshot snapshot() {
        Map<String, Long> stacks = stackCounts.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().sum()));
        return new Snapshot(eventCount.sum(), totalNanos.sum() / 1_000_000, stacks);
    }

    /**
     * @param count       누적 핀닝 이벤트 수
     * @param totalMillis 누적 핀닝 시간(ms)
     * @param stacks      상위 프레임 조합별 발생 횟수 (어디서 핀닝되는지 확인용)
     */
    public record Snapshot(long count, long totalMillis, Map<String, Long> stacks) {

        /** 이 스냅샷에서 이전 스냅샷을 뺀 구간 값 */
        public Snapshot minus(Snapshot earlier) {
            Map<String, Long> diff = new ConcurrentHashMap<>();
            stacks.forEach((k, v) -> {
                long delta = v - earlier.stacks.getOrDefault(k, 0L);
                if (delta > 0) {
                    diff.put(k, delta);
                }
            });
            return new Snapshot(count - earlier.count, totalMillis - earlier.totalMillis, diff);
        }
    }
}
