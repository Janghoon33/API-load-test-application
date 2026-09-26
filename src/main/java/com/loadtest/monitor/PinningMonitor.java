package com.loadtest.monitor;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * 직전 1초 이내의 이벤트를 아직 반영하지 못할 수 있다.
 */
@Slf4j
@Component
public class PinningMonitor {

    /** 서로 다른 스택 키를 추적하는 최대 개수 (메모리 상한) */
    private static final int MAX_TRACKED_STACKS = 50;
    /** 스택 키에 사용하는 프레임 수 */
    private static final int STACK_KEY_DEPTH = 6;

    private final boolean enabled;
    private final LongAdder eventCount = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private final ConcurrentHashMap<String, LongAdder> stackCounts = new ConcurrentHashMap<>();

    private volatile RecordingStream stream;

    public PinningMonitor(@Value("${loadtest.pinning-monitor.enabled:true}") boolean enabled) {
        this.enabled = enabled;
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
