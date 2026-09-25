package com.loadtest.controller;

import com.loadtest.dto.TestConfigDto;
import com.loadtest.dto.TestResultDto;
import com.loadtest.service.TestExecutionService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@Slf4j
@RestController
@RequestMapping("/api/tests")
public class TestController {

    private final TestExecutionService testExecutionService;
    private final ExecutorService testDispatchExecutor;

    public TestController(TestExecutionService testExecutionService,
                          @Qualifier("testDispatchExecutor") ExecutorService testDispatchExecutor) {
        this.testExecutionService = testExecutionService;
        this.testDispatchExecutor = testDispatchExecutor;
    }

    /**
     * 부하 테스트 실행 (비동기)
     * POST /api/tests/execute
     */
    @PostMapping("/execute")
    public ResponseEntity<Map<String, String>> executeTest(
            @Valid @RequestBody TestConfigDto config) {

        log.info("테스트 요청 받음: {}", config);

        // testId 미리 생성
        String testId = "test-" + System.currentTimeMillis() + "-" +
                (int)(Math.random() * 1000);

        log.info("생성된 testId: {}", testId);

        // 비동기로 테스트 실행 (WebSocket으로 진행 상황 전송)
        // 전용 가상 스레드 executor에서 실행한다 (예전에는 executor 없이 runAsync를 호출해 공용 ForkJoinPool을 점유했다).
        // submit()은 예외를 삼키므로 여기서 직접 로그를 남긴다.
        testDispatchExecutor.submit(() -> {
            try {
                testExecutionService.executeTestWithId(testId, config);
            } catch (Exception e) {
                log.error("[{}] 테스트 실행 중 오류", testId, e);
            }
        });

        // 즉시 testId 반환
        Map<String, String> response = new HashMap<>();
        response.put("testId", testId);
        response.put("status", "STARTED");
        response.put("message", "테스트가 시작되었습니다. WebSocket으로 진행 상황을 확인하세요.");

        return ResponseEntity.accepted().body(response);
    }

    /**
     * 모든 테스트 결과 조회
     * GET /api/tests/results
     */
    @GetMapping("/results")
    public ResponseEntity<List<TestResultDto>> getAllResults() {
        List<TestResultDto> results = testExecutionService.getAllExecutions();
        return ResponseEntity.ok(results);
    }

    /**
     * 특정 테스트 결과 조회
     * GET /api/tests/results/{id}
     */
    @GetMapping("/results/{id}")
    public ResponseEntity<TestResultDto> getResult(@PathVariable Long id) {
        TestResultDto result = testExecutionService.getExecution(id);
        return ResponseEntity.ok(result);
    }

    /**
     * 헬스 체크
     * GET /api/tests/health
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}