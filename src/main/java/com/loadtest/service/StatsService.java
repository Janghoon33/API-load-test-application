package com.loadtest.service;

import com.loadtest.dto.TestResultDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatsService {

    private final TestExecutionService testExecutionService;

    /**
     * 대시보드 통계 요약 생성
     */
    public Map<String, Object> getDashboardSummary() {
        List<TestResultDto> allTests = testExecutionService.getAllExecutions();

        if (allTests.isEmpty()) {
            return createEmptySummary();
        }

        return createSummary(allTests);
    }

    /**
     * 통계 계산
     */
    private Map<String, Object> createSummary(List<TestResultDto> tests) {
        int totalTests = tests.size();

        long totalRequests = tests.stream()
                .mapToLong(TestResultDto::getTotalRequests)
                .sum();

        long totalSuccess = tests.stream()
                .mapToLong(TestResultDto::getSuccessCount)
                .sum();

        long totalFailed = tests.stream()
                .mapToLong(TestResultDto::getFailCount)
                .sum();

        double avgTps = tests.stream()
                .mapToDouble(TestResultDto::getTps)
                .average()
                .orElse(0.0);

        double maxTps = tests.stream()
                .mapToDouble(TestResultDto::getTps)
                .max()
                .orElse(0.0);

        double avgResponseTime = tests.stream()
                .mapToLong(TestResultDto::getAvgResponseTimeMs)
                .average()
                .orElse(0.0);

        double successRate = totalRequests > 0
                ? (totalSuccess * 100.0) / totalRequests
                : 0.0;

        // 최근 5개 테스트
        List<TestResultDto> recentTests = tests.stream()
                .limit(5)
                .toList();

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalTests", totalTests);
        summary.put("totalRequests", totalRequests);
        summary.put("totalSuccess", totalSuccess);
        summary.put("totalFailed", totalFailed);
        summary.put("avgTps", Math.round(avgTps * 100.0) / 100.0);
        summary.put("maxTps", Math.round(maxTps * 100.0) / 100.0);
        summary.put("avgResponseTime", Math.round(avgResponseTime));
        summary.put("successRate", Math.round(successRate * 100.0) / 100.0);
        summary.put("recentTests", recentTests);

        return summary;
    }

    /**
     * 빈 통계 생성
     */
    private Map<String, Object> createEmptySummary() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalTests", 0);
        summary.put("totalRequests", 0);
        summary.put("totalSuccess", 0);
        summary.put("totalFailed", 0);
        summary.put("avgTps", 0.0);
        summary.put("maxTps", 0.0);
        summary.put("avgResponseTime", 0);
        summary.put("successRate", 0.0);
        summary.put("recentTests", List.of());
        return summary;
    }
}