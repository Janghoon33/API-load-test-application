package com.loadtest.service;

/**
 * 한 번의 샘플 시점에 본 런타임 상태. GC·핀닝은 실행 시작 이후의 증가분이다. 피크는 연속 측정이 아니라 관측한 값들의 최댓값이며, 관측 시점은 실행 시작, 워커 기동 직후, 짧은 주기(기본 50ms), 샘플, 종료 시점이다. 관측 주기보다 짧은 스파이크는 놓칠 수 있다.
 *
 * @param heapUsedBytes       현재 힙 사용량
 * @param platformThreads     현재 플랫폼 스레드 수 (가상 스레드는 포함되지 않는다)
 * @param activeWorkers       이 실행에서 아직 끝나지 않은 워커 수 (VIRTUAL이면 가상 스레드 수와 같다)
 * @param carrierParallelism  가상 스레드 캐리어 풀 크기
 * @param peakHeapBytes       실행 중 관측한 최대 힙 사용량
 * @param peakPlatformThreads 실행 중 관측한 최대 플랫폼 스레드 수
 * @param gcCount             실행 중 발생한 GC 횟수 (JVM 전역 증가분)
 * @param gcTimeMs            실행 중 소요된 GC 시간(ms) (JVM 전역 증가분)
 * @param pinnedCount         실행 중 발생한 핀닝 수 (JVM 전역 증가분, 최대 약 1초 지연)
 * @param pinnedTimeMs        실행 중 핀닝 누적 시간(ms)
 */
public record RuntimeSnapshot(
        long heapUsedBytes,
        int platformThreads,
        int activeWorkers,
        int carrierParallelism,
        long peakHeapBytes,
        int peakPlatformThreads,
        long gcCount,
        long gcTimeMs,
        long pinnedCount,
        long pinnedTimeMs) {
}
