package com.loadtest.dto;

import org.HdrHistogram.Histogram;

/**
 * 응답 시간 백분위 (단위: ms). 응답이 한 건도 없으면 모두 0이다.
 */
public record PercentilesDto(double p50, double p90, double p95, double p99, double p999) {

    /** {@link com.loadtest.service.TestRunContext}가 기록하는 히스토그램의 단위(마이크로초) */
    private static final double MICROS_PER_MS = 1000.0;

    public static PercentilesDto from(Histogram histogram) {
        if (histogram.getTotalCount() == 0) {
            return new PercentilesDto(0, 0, 0, 0, 0);
        }
        return new PercentilesDto(
                histogram.getValueAtPercentile(50.0) / MICROS_PER_MS,
                histogram.getValueAtPercentile(90.0) / MICROS_PER_MS,
                histogram.getValueAtPercentile(95.0) / MICROS_PER_MS,
                histogram.getValueAtPercentile(99.0) / MICROS_PER_MS,
                histogram.getValueAtPercentile(99.9) / MICROS_PER_MS);
    }
}
