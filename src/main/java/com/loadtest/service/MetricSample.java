package com.loadtest.service;

import com.loadtest.dto.PercentilesDto;

/**
 * 샘플러가 한 번 측정한 값.
 *
 * @param completed     지금까지 완료된 요청 수 (누적)
 * @param success       지금까지 성공한 요청 수 (누적)
 * @param fail          지금까지 실패한 요청 수 (누적)
 * @param instantTps    직전 샘플 이후 구간의 초당 완료 건수
 * @param intervalAvgMs 직전 샘플 이후 구간에 기록된 응답의 평균 시간(ms). 구간에 응답이 없으면 0
 * @param cumulative    실행 시작부터의 누적 응답 시간 백분위
 * @param elapsedMs     실행 시작 이후 경과 시간(ms)
 * @param runtime       이 시점의 JVM·워커 상태
 */
public record MetricSample(
        int completed,
        int success,
        int fail,
        double instantTps,
        double intervalAvgMs,
        PercentilesDto cumulative,
        long elapsedMs,
        RuntimeSnapshot runtime) {
}
