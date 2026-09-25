package com.loadtest.service;

import com.loadtest.dto.TestConfigDto;
import com.loadtest.dto.TestResultDto;
import com.loadtest.dto.RealtimeMetricDto;
import com.loadtest.entity.TestExecution;
import com.loadtest.repository.TestExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

@Slf4j
@Service
public class TestExecutionService {

    private final RunExecutorFactory runExecutorFactory;
    private final HttpClient httpClient;
    private final TestExecutionRepository executionRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public TestExecutionService(
            RunExecutorFactory runExecutorFactory,
            HttpClient httpClient,
            TestExecutionRepository executionRepository,
            SimpMessagingTemplate messagingTemplate) {
        this.runExecutorFactory = runExecutorFactory;
        this.httpClient = httpClient;
        this.executionRepository = executionRepository;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 부하 테스트 실행
     */
    public TestResultDto executeTest(TestConfigDto config) {
        return executeTestWithId(generateTestId(), config);
    }

    /**
     * testId를 받아서 실행 (WebSocket용)
     */
    public TestResultDto executeTestWithId(String testId, TestConfigDto config) {
        log.info("[{}] 테스트 시작 - Type: {}, Threads: {}, Requests/Thread: {}",
                testId, config.getThreadType(), config.getVirtualThreads(), config.getRequestsPerThread());

        // 이 실행만의 상태 (카운터, 에러 집계). 서비스 필드로 두면 실행 간에 섞인다.
        TestRunContext ctx = new TestRunContext(testId, config);

        // 초기 상태 전송
        sendRealtimeMetric(ctx, 0, "RUNNING");

        List<Future<?>> workers = new ArrayList<>();

        // 실행마다 새 executor를 만들고, try-with-resources를 벗어날 때 close()가 모든 워커의 완료를 기다린다.
        try (ExecutorService executor = runExecutorFactory.create(config.getThreadType(), testId)) {
            for (int i = 0; i < config.getVirtualThreads(); i++) {
                final int threadId = i;
                workers.add(executor.submit(() -> runWorker(ctx, threadId)));
            }
        }

        // submit()은 예외를 Future에 가두므로, 워커가 비정상 종료했다면 조용히 넘어가지 않고 실패로 드러낸다
        for (Future<?> worker : workers) {
            if (worker.state() == Future.State.FAILED) {
                throw new IllegalStateException("[" + testId + "] 워커가 비정상 종료했습니다", worker.exceptionNow());
            }
        }

        long endMillis = System.currentTimeMillis();
        LocalDateTime endTime = LocalDateTime.now();

        // 최종 결과 계산
        TestResultDto result = buildResult(ctx, endTime, endMillis);

        // DB에 저장하고 ID 받아오기
        Long executionId = saveExecution(config, result, ctx.startedAt(), endTime);
        result.setExecutionId(executionId);

        // 완료 상태 전송
        sendRealtimeMetric(ctx, ctx.totalRequests(), "COMPLETED");

        // 최종 결과 전송
        messagingTemplate.convertAndSend("/topic/test-complete/" + testId, result);

        log.info("[{}] 테스트 완료 - ID: {}, Success: {}, Fail: {}, TPS: {}",
                testId, executionId, result.getSuccessCount(), result.getFailCount(), result.getTps());

        return result;
    }

    /**
     * 가상 사용자 한 명: 설정된 횟수만큼 요청을 순서대로 실행한다.
     */
    private void runWorker(TestRunContext ctx, int threadId) {
        for (int j = 0; j < ctx.config().getRequestsPerThread(); j++) {
            executeRequest(ctx, threadId, j);

            // 실시간 메트릭 전송
            int completed = ctx.completedCount();

            // 100개마다 또는 완료 시 전송
            if (completed % 100 == 0 || completed == ctx.totalRequests()) {
                sendRealtimeMetric(ctx, completed, "RUNNING");
            }
        }
    }

    /**
     * 실시간 메트릭 전송
     */
    private void sendRealtimeMetric(TestRunContext ctx, int completed, String status) {
        long now = System.currentTimeMillis();
        long elapsed = now - ctx.startMillis();
        int totalRequests = ctx.totalRequests();
        double progress = totalRequests > 0 ? (completed * 100.0) / totalRequests : 0.0;
        double currentTps = elapsed > 0 ? (completed * 1000.0) / elapsed : 0.0;

        RealtimeMetricDto metric = RealtimeMetricDto.builder()
                .testId(ctx.testId())
                .totalRequests(totalRequests)
                .completedRequests(completed)
                .successCount(ctx.successCount())
                .failCount(ctx.failCount())
                .progress(progress)
                .currentTps(currentTps)
                .avgResponseTimeMs(0)
                .elapsedTimeMs(elapsed)
                .timestamp(now)
                .status(status)
                .build();

        messagingTemplate.convertAndSend("/topic/metrics/" + ctx.testId(), metric);
    }

    /**
     * 테스트 ID 생성
     */
    private String generateTestId() {
        return "test-" + System.currentTimeMillis() + "-" +
                (int)(Math.random() * 1000);
    }

    /**
     * 개별 HTTP 요청 실행
     */
    private void executeRequest(TestRunContext ctx, int threadId, int requestId) {
        TestConfigDto config = ctx.config();
        try {
            long reqStart = System.currentTimeMillis();

            // HttpRequest 빌드
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(config.getUrl()));

            // HTTP 메서드 설정 (null 체크)
            String method = config.getMethodOrDefault();
            switch (method.toUpperCase()) {
                case "POST":
                    requestBuilder.POST(HttpRequest.BodyPublishers.ofString(
                            config.getBody() != null ? config.getBody() : ""));
                    break;
                case "PUT":
                    requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(
                            config.getBody() != null ? config.getBody() : ""));
                    break;
                case "DELETE":
                    requestBuilder.DELETE();
                    break;
                default:
                    requestBuilder.GET();
            }

            // 헤더 추가
            if (config.getHeaders() != null) {
                config.getHeaders().forEach(requestBuilder::header);
            }

            HttpRequest request = requestBuilder
                    .timeout(Duration.ofSeconds(10))
                    .build();

            // 동기 요청 (가상 스레드에서는 블로킹 시 자동 양보)
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            // 통계 업데이트
            ctx.recordResponse(System.currentTimeMillis() - reqStart);

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                ctx.recordSuccess();
            } else {
                // HTTP 에러 분류
                String errorType = classifyHttpError(response.statusCode());
                ctx.recordHttpFailure(errorType);
                log.warn("Thread-{} Request-{} HTTP Error: {} - {}",
                        threadId, requestId, response.statusCode(), errorType);
            }

        } catch (java.net.http.HttpTimeoutException e) {
            ctx.recordException("TIMEOUT");
            log.error("Thread-{} Request-{} Timeout", threadId, requestId);

        } catch (java.net.ConnectException e) {
            ctx.recordException("CONNECTION_FAILED");
            log.error("Thread-{} Request-{} Connection Failed", threadId, requestId);

        } catch (Exception e) {
            ctx.recordException("UNKNOWN");
            log.error("Thread-{} Request-{} Error: {}", threadId, requestId, e.getMessage());
        }
    }

    /**
     * HTTP 에러 분류
     */
    private String classifyHttpError(int statusCode) {
        if (statusCode >= 400 && statusCode < 500) {
            return "CLIENT_ERROR_" + statusCode;
        } else if (statusCode >= 500) {
            return "SERVER_ERROR_" + statusCode;
        }
        return "HTTP_ERROR_" + statusCode;
    }

    /**
     * 결과 DTO 생성
     */
    private TestResultDto buildResult(TestRunContext ctx, LocalDateTime endTime, long endMillis) {
        int success = ctx.successCount();
        int fail = ctx.failCount();
        int totalRequests = success + fail;
        long duration = endMillis - ctx.startMillis();

        return TestResultDto.builder()
                .threadType(ctx.config().getThreadType().name())
                .totalRequests(totalRequests)
                .successCount(success)
                .failCount(fail)
                .avgResponseTimeMs(totalRequests > 0 ? ctx.totalResponseTimeMs() / totalRequests : 0)
                .minResponseTimeMs(ctx.minResponseTimeMs())
                .maxResponseTimeMs(ctx.maxResponseTimeMs())
                .totalDurationMs(duration)
                .tps(duration > 0 ? (totalRequests * 1000.0) / duration : 0)
                .errorBreakdown(ctx.errorBreakdown())
                .startedAt(ctx.startedAt())
                .completedAt(endTime)
                .build();
    }

    /**
     * 실행 결과 DB 저장 후 ID 반환
     */
    private Long saveExecution(TestConfigDto config, TestResultDto result,
                               LocalDateTime startTime, LocalDateTime endTime) {
        TestExecution execution = TestExecution.builder()
                .url(config.getUrl())
                .threadType(config.getThreadType().name())
                .virtualThreads(config.getVirtualThreads())
                .requestsPerThread(config.getRequestsPerThread())
                .totalRequests(result.getTotalRequests())
                .successCount(result.getSuccessCount())
                .failCount(result.getFailCount())
                .avgResponseTimeMs(result.getAvgResponseTimeMs())
                .minResponseTimeMs(result.getMinResponseTimeMs())
                .maxResponseTimeMs(result.getMaxResponseTimeMs())
                .totalDurationMs(result.getTotalDurationMs())
                .tps(result.getTps())
                .startedAt(startTime)
                .completedAt(endTime)
                .build();

        TestExecution saved = executionRepository.save(execution);
        return saved.getId();
    }

    /**
     * 모든 실행 히스토리 조회
     */
    public List<TestResultDto> getAllExecutions() {
        return executionRepository.findAllByOrderByStartedAtDesc().stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * 특정 실행 결과 조회
     */
    public TestResultDto getExecution(Long id) {
        TestExecution execution = executionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Execution not found: " + id));
        return toDto(execution);
    }

    /**
     * Entity를 DTO로 변환
     */
    private TestResultDto toDto(TestExecution entity) {
        return TestResultDto.builder()
                .executionId(entity.getId())
                .threadType(entity.getThreadType())
                .totalRequests(entity.getTotalRequests())
                .successCount(entity.getSuccessCount())
                .failCount(entity.getFailCount())
                .avgResponseTimeMs(entity.getAvgResponseTimeMs())
                .minResponseTimeMs(entity.getMinResponseTimeMs())
                .maxResponseTimeMs(entity.getMaxResponseTimeMs())
                .totalDurationMs(entity.getTotalDurationMs())
                .tps(entity.getTps())
                .startedAt(entity.getStartedAt())
                .completedAt(entity.getCompletedAt())
                .build();
    }
}