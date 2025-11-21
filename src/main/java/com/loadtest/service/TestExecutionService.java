package com.loadtest.service;

import com.loadtest.dto.TestConfigDto;
import com.loadtest.dto.TestResultDto;
import com.loadtest.entity.TestExecution;
import com.loadtest.repository.TestExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class TestExecutionService {

    private final ExecutorService virtualExecutor;
    private final ExecutorService platformExecutor;
    private final HttpClient httpClient;
    private final TestExecutionRepository executionRepository;

    public TestExecutionService(
            @Qualifier("virtualThreadExecutor") ExecutorService virtualExecutor,
            @Qualifier("platformThreadExecutor") ExecutorService platformExecutor,
            HttpClient httpClient,
            TestExecutionRepository executionRepository) {
        this.virtualExecutor = virtualExecutor;
        this.platformExecutor = platformExecutor;
        this.httpClient = httpClient;
        this.executionRepository = executionRepository;
    }

    /**
     * 부하 테스트 실행
     */
    public TestResultDto executeTest(TestConfigDto config) {
        log.info("테스트 시작 - Type: {}, Threads: {}, Requests/Thread: {}",
                config.getThreadType(), config.getVirtualThreads(), config.getRequestsPerThread());

        ExecutorService executor = config.getThreadType() == TestConfigDto.ThreadType.VIRTUAL
                ? virtualExecutor
                : platformExecutor;

        LocalDateTime startTime = LocalDateTime.now();
        long startMillis = System.currentTimeMillis();

        // 통계 수집용 변수들
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicLong totalResponseTime = new AtomicLong(0);
        AtomicLong minResponseTime = new AtomicLong(Long.MAX_VALUE);
        AtomicLong maxResponseTime = new AtomicLong(0);

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // 가상 스레드 또는 플랫폼 스레드 생성
        for (int i = 0; i < config.getVirtualThreads(); i++) {
            final int threadId = i;

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                // 각 스레드가 설정된 횟수만큼 요청 실행
                for (int j = 0; j < config.getRequestsPerThread(); j++) {
                    executeRequest(config, threadId, j,
                            successCount, failCount, totalResponseTime,
                            minResponseTime, maxResponseTime);
                }
            }, executor);

            futures.add(future);
        }

        // 모든 스레드 완료 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long endMillis = System.currentTimeMillis();
        LocalDateTime endTime = LocalDateTime.now();

        // 결과 계산
        TestResultDto result = buildResult(config, successCount.get(), failCount.get(),
                totalResponseTime.get(), minResponseTime.get(), maxResponseTime.get(),
                startTime, endTime, startMillis, endMillis);

        // DB에 저장하고 ID 받아오기
        Long executionId = saveExecution(config, result, startTime, endTime);
        result.setExecutionId(executionId);

        log.info("테스트 완료 - ID: {}, Success: {}, Fail: {}, TPS: {}",
                executionId, result.getSuccessCount(), result.getFailCount(), result.getTps());

        return result;
    }

    /**
     * 개별 HTTP 요청 실행
     */
    private void executeRequest(TestConfigDto config, int threadId, int requestId,
                                AtomicInteger successCount, AtomicInteger failCount,
                                AtomicLong totalResponseTime, AtomicLong minResponseTime,
                                AtomicLong maxResponseTime) {
        try {
            long reqStart = System.currentTimeMillis();

            // HttpRequest 빌드
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(config.getUrl()));

            // HTTP 메서드 설정
            switch (config.getMethod().toUpperCase()) {
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

            HttpRequest request = requestBuilder.build();

            // 동기 요청 (가상 스레드에서는 블로킹 시 자동 양보)
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            long reqTime = System.currentTimeMillis() - reqStart;

            // 통계 업데이트
            totalResponseTime.addAndGet(reqTime);
            updateMin(minResponseTime, reqTime);
            updateMax(maxResponseTime, reqTime);

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                successCount.incrementAndGet();
            } else {
                failCount.incrementAndGet();
                log.warn("Thread-{} Request-{} 실패: Status {}",
                        threadId, requestId, response.statusCode());
            }

        } catch (Exception e) {
            failCount.incrementAndGet();
            log.error("Thread-{} Request-{} 오류: {}", threadId, requestId, e.getMessage());
        }
    }

    /**
     * 최소값 원자적 업데이트
     */
    private void updateMin(AtomicLong min, long value) {
        long current;
        do {
            current = min.get();
            if (value >= current) return;
        } while (!min.compareAndSet(current, value));
    }

    /**
     * 최대값 원자적 업데이트
     */
    private void updateMax(AtomicLong max, long value) {
        long current;
        do {
            current = max.get();
            if (value <= current) return;
        } while (!max.compareAndSet(current, value));
    }

    /**
     * 결과 DTO 생성
     */
    private TestResultDto buildResult(TestConfigDto config, int success, int fail,
                                      long totalRespTime, long minRespTime, long maxRespTime,
                                      LocalDateTime startTime, LocalDateTime endTime,
                                      long startMillis, long endMillis) {
        int totalRequests = success + fail;
        long duration = endMillis - startMillis;

        return TestResultDto.builder()
                .threadType(config.getThreadType().name())
                .totalRequests(totalRequests)
                .successCount(success)
                .failCount(fail)
                .avgResponseTimeMs(totalRequests > 0 ? totalRespTime / totalRequests : 0)
                .minResponseTimeMs(minRespTime == Long.MAX_VALUE ? 0 : minRespTime)
                .maxResponseTimeMs(maxRespTime)
                .totalDurationMs(duration)
                .tps(duration > 0 ? (totalRequests * 1000.0) / duration : 0)
                .startedAt(startTime)
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