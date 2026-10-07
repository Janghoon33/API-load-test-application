package com.loadtest.monitor;

import org.springframework.stereotype.Component;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;

/**
 * 현재 JVM의 힙·스레드·GC·핀닝 값을 읽는다.
 * <p>
 * 읽는 값은 모두 <b>JVM 전역</b>이다. 실행별 값(증가분, 피크)은 {@link com.loadtest.service.RunMetricSampler}가
 * 실행 시작 시점의 값과 비교해서 구한다. 따라서 동시에 도는 실행이 있으면 GC·핀닝 증가분은 서로 섞일 수 있다.
 * <p>
 * 한계: {@link ThreadMXBean#getThreadCount()}는 <b>플랫폼 스레드만</b> 센다(가상 스레드는 포함되지 않는다).
 * 핀닝은 JFR 스트림이 이벤트를 배치로 전달하므로 최대 약 1초 늦게 반영된다.
 */
@Component
public class JvmMetricsProbe {

    private static final String PARALLELISM_PROPERTY = "jdk.virtualThreadScheduler.parallelism";

    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final PinningMonitor pinningMonitor;

    public JvmMetricsProbe(PinningMonitor pinningMonitor) {
        this.pinningMonitor = pinningMonitor;
    }

    public Reading read() {
        long gcCount = 0;
        long gcTimeMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            // 지원하지 않는 컬렉터는 -1을 돌려준다
            gcCount += Math.max(gc.getCollectionCount(), 0);
            gcTimeMs += Math.max(gc.getCollectionTime(), 0);
        }
        PinningMonitor.Snapshot pinning = pinningMonitor.snapshot();
        return new Reading(
                memory.getHeapMemoryUsage().getUsed(),
                threads.getThreadCount(),
                gcCount,
                gcTimeMs,
                carrierParallelism(),
                pinning.count(),
                pinning.totalMillis());
    }

    /** 가상 스레드 스케줄러(캐리어 풀)의 병렬도. 속성이 없거나 잘못되면 JDK 기본값(프로세서 수)이다. */
    private static int carrierParallelism() {
        int fallback = Runtime.getRuntime().availableProcessors();
        String configured = System.getProperty(PARALLELISM_PROPERTY);
        if (configured == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(configured.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * JVM 전역 누적값의 한 시점 읽기.
     *
     * @param heapUsedBytes      현재 힙 사용량
     * @param platformThreads    현재 플랫폼 스레드 수 (가상 스레드 제외)
     * @param gcCount            JVM 시작 이후 GC 누적 횟수
     * @param gcTimeMs           JVM 시작 이후 GC 누적 시간(ms)
     * @param carrierParallelism 가상 스레드 캐리어 풀 크기
     * @param pinnedCount        JVM 시작 이후 핀닝 이벤트 누적 수
     * @param pinnedTimeMs       JVM 시작 이후 핀닝 누적 시간(ms)
     */
    public record Reading(
            long heapUsedBytes,
            int platformThreads,
            long gcCount,
            long gcTimeMs,
            int carrierParallelism,
            long pinnedCount,
            long pinnedTimeMs) {
    }
}
