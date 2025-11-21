package com.loadtest.controller;

import com.loadtest.dto.TestConfigDto;
import com.loadtest.dto.TestResultDto;
import com.loadtest.service.TestExecutionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/tests")
@RequiredArgsConstructor
public class TestController {

    private final TestExecutionService testExecutionService;

    /**
     * 부하 테스트 실행
     * POST /api/tests/execute
     */
    @PostMapping("/execute")
    public ResponseEntity<TestResultDto> executeTest(
            @Valid @RequestBody TestConfigDto config) {

        log.info("테스트 요청 받음: {}", config);

        TestResultDto result = testExecutionService.executeTest(config);

        return ResponseEntity.ok(result);
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
