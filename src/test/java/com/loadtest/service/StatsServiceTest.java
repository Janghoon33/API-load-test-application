package com.loadtest.service;

import com.loadtest.dto.TestResultDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    @Mock
    private TestExecutionService testExecutionService;

    private StatsService statsService;

    @Test
    void 여러_테스트_결과를_합산해서_대시보드_요약을_만든다() {
        statsService = new StatsService(testExecutionService);

        TestResultDto first = TestResultDto.builder()
                .totalRequests(100).successCount(90).failCount(10)
                .tps(50.0).avgResponseTimeMs(20)
                .build();
        TestResultDto second = TestResultDto.builder()
                .totalRequests(200).successCount(180).failCount(20)
                .tps(150.0).avgResponseTimeMs(30)
                .build();
        given(testExecutionService.getAllExecutions()).willReturn(List.of(first, second));

        Map<String, Object> summary = statsService.getDashboardSummary();

        assertThat(summary)
                .containsEntry("totalTests", 2)
                .containsEntry("totalRequests", 300L)
                .containsEntry("totalSuccess", 270L)
                .containsEntry("totalFailed", 30L)
                .containsEntry("avgTps", 100.0)
                .containsEntry("maxTps", 150.0)
                .containsEntry("avgResponseTime", 25L)
                .containsEntry("successRate", 90.0);
    }

    @Test
    void 실행_이력이_없으면_모든_값이_0인_빈_요약을_반환한다() {
        statsService = new StatsService(testExecutionService);
        given(testExecutionService.getAllExecutions()).willReturn(List.of());

        Map<String, Object> summary = statsService.getDashboardSummary();

        assertThat(summary)
                .containsEntry("totalTests", 0)
                .containsEntry("totalRequests", 0)
                .containsEntry("totalSuccess", 0)
                .containsEntry("totalFailed", 0)
                .containsEntry("avgTps", 0.0)
                .containsEntry("maxTps", 0.0)
                .containsEntry("avgResponseTime", 0)
                .containsEntry("successRate", 0.0);
        assertThat((List<?>) summary.get("recentTests")).isEmpty();
    }

    @Test
    void 최근_테스트_목록은_5건으로_제한된다() {
        statsService = new StatsService(testExecutionService);

        List<TestResultDto> sevenResults = List.of(
                dto(1), dto(2), dto(3), dto(4), dto(5), dto(6), dto(7));
        given(testExecutionService.getAllExecutions()).willReturn(sevenResults);

        Map<String, Object> summary = statsService.getDashboardSummary();

        @SuppressWarnings("unchecked")
        List<TestResultDto> recentTests = (List<TestResultDto>) summary.get("recentTests");
        assertThat(recentTests).hasSize(5);
        assertThat(recentTests).containsExactlyElementsOf(sevenResults.subList(0, 5));
    }

    private TestResultDto dto(long executionId) {
        return TestResultDto.builder()
                .executionId(executionId)
                .totalRequests(10).successCount(10).failCount(0)
                .tps(1.0).avgResponseTimeMs(1)
                .build();
    }
}
