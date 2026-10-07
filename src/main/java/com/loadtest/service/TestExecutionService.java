package com.loadtest.service;

import com.loadtest.dto.PercentilesDto;
import com.loadtest.dto.TestConfigDto;
import com.loadtest.dto.TestResultDto;
import com.loadtest.dto.RealtimeMetricDto;
import com.loadtest.entity.TestExecution;
import com.loadtest.monitor.JvmMetricsProbe;
import com.loadtest.monitor.PinningMonitor;
import com.loadtest.repository.TestExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class TestExecutionService {

    private final RunExecutorFactory runExecutorFactory;
    private final PlatformThreadBudget platformThreadBudget;
    private final HttpClient httpClient;
    private final TestExecutionRepository executionRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final JvmMetricsProbe jvmMetricsProbe;
    private final PinningMonitor pinningMonitor;
    private final ScheduledExecutorService metricSamplerScheduler;
    private final Duration sampleInterval;
    private final Duration peakSampleInterval;

    public TestExecutionService(
            RunExecutorFactory runExecutorFactory,
            PlatformThreadBudget platformThreadBudget,
            HttpClient httpClient,
            TestExecutionRepository executionRepository,
            SimpMessagingTemplate messagingTemplate,
            JvmMetricsProbe jvmMetricsProbe,
            PinningMonitor pinningMonitor,
            @Qualifier("metricSamplerScheduler") ScheduledExecutorService metricSamplerScheduler,
            @Value("${loadtest.metrics.sample-interval:1s}") Duration sampleInterval,
            @Value("${loadtest.metrics.peak-sample-interval:50ms}") Duration peakSampleInterval) {
        this.runExecutorFactory = runExecutorFactory;
        this.platformThreadBudget = platformThreadBudget;
        this.httpClient = httpClient;
        this.executionRepository = executionRepository;
        this.messagingTemplate = messagingTemplate;
        this.jvmMetricsProbe = jvmMetricsProbe;
        this.pinningMonitor = pinningMonitor;
        this.metricSamplerScheduler = metricSamplerScheduler;
        this.sampleInterval = sampleInterval;
        this.peakSampleInterval = peakSampleInterval;
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

        // PLATFORM 실행은 시작하기 전에 전역 플랫폼 스레드 예산을 획득한다(부족하면 대기).
        // 시작 시각 기록(TestRunContext)은 그 뒤에 해야 대기 시간이 TPS 계산에 섞이지 않는다.
        // 성공·실패·예외 어느 경로로 끝나든 반드시 반환한다.
        int platformPermits = platformThreadBudget.acquireFor(config);
        FinishedRun finished;
        try {
            finished = runWorkers(testId, config, platformPermits);
        } finally {
            platformThreadBudget.release(platformPermits);
        }
        // 핀닝 전달을 기다리는 동안에는 예산을 쥐고 있을 이유가 없으므로 반환한 뒤에 마무리한다
        return complete(testId, config, finished);
    }

    /** 워커가 모두 끝난 시점의 실행 상태. 종료 시각은 이후의 전달 확정 대기가 섞이지 않도록 여기서 확정한다. */
    private record FinishedRun(TestRunContext ctx, RunMetricSampler sampler,
                               long endNanos, long endMillis, LocalDateTime endTime) {
    }

    private FinishedRun runWorkers(String testId, TestConfigDto config, int platformPermits) {
        // 이 실행만의 상태 (카운터, 에러 집계). 서비스 필드로 두면 실행 간에 섞인다.
        TestRunContext ctx = new TestRunContext(testId, config);

        // 지표 계산·전송은 워커가 아니라 샘플러 스케줄러가 주기적으로 맡는다. 워커는 요청만 수행한다.
        RunMetricSampler sampler = new RunMetricSampler(ctx, System.nanoTime(), jvmMetricsProbe::read);
        sendRealtimeMetric(ctx, sampler.sample(System.nanoTime()), "RUNNING");

        // 틱과 실행 종료가 겹쳐도 종료(COMPLETED) 뒤에 RUNNING이 늦게 도착하지 않도록 같은 락으로 직렬화한다
        Object tickLock = new Object();
        AtomicBoolean sampling = new AtomicBoolean(true);
        ScheduledFuture<?> ticker = metricSamplerScheduler.scheduleAtFixedRate(() -> {
            synchronized (tickLock) {
                if (!sampling.get()) {
                    return;
                }
                try {
                    sendRealtimeMetric(ctx, sampler.sample(System.nanoTime()), "RUNNING");
                } catch (RuntimeException e) {
                    // 한 번의 전송 실패로 이후 샘플링이 영구히 멈추지 않게 한다 (예외를 던지면 주기 실행이 취소된다)
                    log.warn("[{}] 메트릭 샘플 전송 실패: {}", testId, e.toString());
                }
            }
        }, sampleInterval.toMillis(), sampleInterval.toMillis(), TimeUnit.MILLISECONDS);

        // 1초 주기 샘플만으로는 그보다 짧게 끝나는 실행의 피크(PLATFORM 풀 스레드, 힙)를 놓친다.
        // 전송 없이 피크만 읽는 관측을 짧은 주기로 따로 돌린다.
        ScheduledFuture<?> peakObserver = metricSamplerScheduler.scheduleAtFixedRate(() -> {
            try {
                sampler.observePeak();
            } catch (RuntimeException e) {
                log.warn("[{}] 피크 관측 실패: {}", testId, e.toString());
            }
        }, peakSampleInterval.toMillis(), peakSampleInterval.toMillis(), TimeUnit.MILLISECONDS);

        List<Future<?>> workers = new ArrayList<>();

        try {
            // 실행마다 새 executor를 만들고, try-with-resources를 벗어날 때 close()가 모든 워커의 완료를 기다린다.
            try (ExecutorService executor = runExecutorFactory.create(config.getThreadType(), testId, platformPermits)) {
                for (int i = 0; i < config.getVirtualThreads(); i++) {
                    final int threadId = i;
                    workers.add(executor.submit(() -> runWorker(ctx, threadId)));
                }
                // close()가 워커 완료를 기다리기 전에 한 번 관측한다. 이 시점에는 PLATFORM 풀이 최대 크기이므로,
                // 아무리 짧은 실행이어도 실행 중의 값이 최소 한 번은 피크에 반영된다.
                sampler.observePeak();
            }
        } finally {
            // 성공·실패·인터럽트 어느 경로로 끝나든 샘플러는 반드시 멈춘다
            synchronized (tickLock) {
                sampling.set(false);
            }
            ticker.cancel(false);
            peakObserver.cancel(false);
        }

        // submit()은 예외를 Future에 가두므로, 워커가 비정상 종료했다면 조용히 넘어가지 않고 실패로 드러낸다
        int unfinishedWorkers = 0;
        for (Future<?> worker : workers) {
            Future.State state = worker.state();
            if (state == Future.State.FAILED) {
                throw new IllegalStateException("[" + testId + "] 워커가 비정상 종료했습니다", worker.exceptionNow());
            }
            if (state != Future.State.SUCCESS) {
                unfinishedWorkers++;
            }
        }

        // 인터럽트로 close()가 shutdownNow()로 바뀌면 큐에서 시작도 못 한 워커가 있을 수 있고(Future는 끝나지 않은 상태로 남는다),
        // 진행 중이던 워커도 중간에 멈춘다. 모든 요청은 정확히 한 번 완료 처리되므로 completed == total이 아니면
        // 일부만 수행된 것이다. 그런 결과를 저장하거나 COMPLETED로 알리지 않고 실패로 끝낸다.
        if (unfinishedWorkers > 0 || ctx.completedCount() < ctx.totalRequests()) {
            throw new IllegalStateException(String.format(
                    "[%s] 실행이 중단되어 일부 요청만 수행되었습니다 (완료 %d/%d, 미완료 워커 %d)",
                    testId, ctx.completedCount(), ctx.totalRequests(), unfinishedWorkers));
        }

        // 대기 시간이 소요 시간·TPS에 섞이지 않도록 종료 시각은 전달 확정을 기다리기 전에 확정한다
        return new FinishedRun(ctx, sampler, System.nanoTime(), System.currentTimeMillis(), LocalDateTime.now());
    }

    /**
     * 최종 결과를 만들어 저장하고 전송한다.
     * <p>
     * JFR 핀닝 이벤트는 배치로 늦게 전달되므로, 전달을 확정하기 전에 최종 스냅샷을 만들면 마지막 구간의 핀닝이
     * 결과(DB)에서 영구히 누락된다. 그래서 스냅샷 전에 모니터에 전달 확정을 요청한다(최대 설정된 제한 시간).
     * 모니터가 꺼져 있으면 기다리지 않는다.
     */
    private TestResultDto complete(String testId, TestConfigDto config, FinishedRun finished) {
        TestRunContext ctx = finished.ctx();
        long endMillis = finished.endMillis();
        LocalDateTime endTime = finished.endTime();

        if (pinningMonitor.isRunning()) {
            pinningMonitor.awaitDelivery();
        }

        // 마지막 구간까지 반영한 최종 값 (백분위 포함)
        MetricSample finalSample = finished.sampler().finish(finished.endNanos());

        // 최종 결과 계산
        TestResultDto result = buildResult(ctx, finalSample, endTime, endMillis);

        // DB에 저장하고 ID 받아오기
        Long executionId = saveExecution(config, result, ctx.startedAt(), endTime);
        result.setExecutionId(executionId);

        // 완료 상태 전송
        sendRealtimeMetric(ctx, finalSample, "COMPLETED");

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
        ctx.workerStarted();
        try {
            for (int j = 0; j < ctx.config().getRequestsPerThread(); j++) {
                executeRequest(ctx, threadId, j);
            }
        } finally {
            ctx.workerFinished();
        }
    }

    /**
     * 실시간 메트릭 전송
     */
    private void sendRealtimeMetric(TestRunContext ctx, MetricSample sample, String status) {
        int totalRequests = ctx.totalRequests();
        double progress = totalRequests > 0 ? (sample.completed() * 100.0) / totalRequests : 0.0;

        RealtimeMetricDto metric = RealtimeMetricDto.builder()
                .testId(ctx.testId())
                .totalRequests(totalRequests)
                .completedRequests(sample.completed())
                .successCount(sample.success())
                .failCount(sample.fail())
                .progress(progress)
                .currentTps(sample.instantTps())
                .avgResponseTimeMs(Math.round(sample.intervalAvgMs()))
                .p95Ms(sample.cumulative().p95())
                .p99Ms(sample.cumulative().p99())
                .heapUsedBytes(sample.runtime().heapUsedBytes())
                .platformThreadCount(sample.runtime().platformThreads())
                .activeWorkers(sample.runtime().activeWorkers())
                .carrierParallelism(sample.runtime().carrierParallelism())
                .pinnedCount(sample.runtime().pinnedCount())
                .elapsedTimeMs(sample.elapsedMs())
                .timestamp(System.currentTimeMillis())
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
    // 테스트에서 인터럽트 처리를 직접 검증하기 위해 package-private으로 둔다
    void executeRequest(TestRunContext ctx, int threadId, int requestId) {
        TestConfigDto config = ctx.config();
        try {
            long reqStart = System.nanoTime();

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
            ctx.recordResponseNanos(System.nanoTime() - reqStart);

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                ctx.recordSuccess();
            } else {
                // HTTP 에러 분류
                String errorType = classifyHttpError(response.statusCode());
                ctx.recordHttpFailure(errorType);
                // 요청 단위 로그는 옵션이다. 에러는 errorBreakdown에 집계되므로 기본(OFF)에서도 잃는 정보가 없다.
                if (config.isEnableLogging()) {
                    log.warn("Thread-{} Request-{} HTTP Error: {} - {}",
                            threadId, requestId, response.statusCode(), errorType);
                }
            }

        } catch (java.net.http.HttpTimeoutException e) {
            ctx.recordException("TIMEOUT");
            if (config.isEnableLogging()) {
                log.error("Thread-{} Request-{} Timeout", threadId, requestId);
            }

        } catch (java.net.ConnectException e) {
            ctx.recordException("CONNECTION_FAILED");
            if (config.isEnableLogging()) {
                log.error("Thread-{} Request-{} Connection Failed", threadId, requestId);
            }

        } catch (InterruptedException e) {
            // 중단 요청이다. 대상 서버의 실패가 아니므로 통계에 넣지 않고, 플래그를 복원해 워커가 멈추게 한다.
            Thread.currentThread().interrupt();

        } catch (Exception e) {
            ctx.recordException("UNKNOWN");
            if (config.isEnableLogging()) {
                log.error("Thread-{} Request-{} Error: {}", threadId, requestId, e.getMessage());
            }
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
    private TestResultDto buildResult(TestRunContext ctx, MetricSample finalSample,
                                      LocalDateTime endTime, long endMillis) {
        PercentilesDto percentiles = finalSample.cumulative();
        RuntimeSnapshot runtime = finalSample.runtime();
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
                .p50Ms(percentiles.p50())
                .p90Ms(percentiles.p90())
                .p95Ms(percentiles.p95())
                .p99Ms(percentiles.p99())
                .p999Ms(percentiles.p999())
                .peakHeapBytes(runtime.peakHeapBytes())
                .peakPlatformThreads(runtime.peakPlatformThreads())
                .gcCount(runtime.gcCount())
                .gcTimeMs(runtime.gcTimeMs())
                .pinnedCount(runtime.pinnedCount())
                .pinnedTimeMs(runtime.pinnedTimeMs())
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
                .p50Ms(result.getP50Ms())
                .p90Ms(result.getP90Ms())
                .p95Ms(result.getP95Ms())
                .p99Ms(result.getP99Ms())
                .p999Ms(result.getP999Ms())
                .peakHeapBytes(result.getPeakHeapBytes())
                .peakPlatformThreads(result.getPeakPlatformThreads())
                .gcCount(result.getGcCount())
                .gcTimeMs(result.getGcTimeMs())
                .pinnedCount(result.getPinnedCount())
                .pinnedTimeMs(result.getPinnedTimeMs())
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
                .p50Ms(entity.getP50Ms())
                .p90Ms(entity.getP90Ms())
                .p95Ms(entity.getP95Ms())
                .p99Ms(entity.getP99Ms())
                .p999Ms(entity.getP999Ms())
                .peakHeapBytes(entity.getPeakHeapBytes())
                .peakPlatformThreads(entity.getPeakPlatformThreads())
                .gcCount(entity.getGcCount())
                .gcTimeMs(entity.getGcTimeMs())
                .pinnedCount(entity.getPinnedCount())
                .pinnedTimeMs(entity.getPinnedTimeMs())
                .startedAt(entity.getStartedAt())
                .completedAt(entity.getCompletedAt())
                .build();
    }
}