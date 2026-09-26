package com.loadtest.service;

import com.loadtest.dto.TestConfigDto;
import com.loadtest.dto.TestResultDto;
import com.loadtest.entity.TestExecution;
import com.loadtest.repository.TestExecutionRepository;
import com.loadtest.support.FakeTargetServer;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TestExecutionService}의 실행 결과 정확성과 실행 간 격리를 검증한다.
 * <p>
 * 핵심은 B1 버그 재현: {@code errorBreakdown}이 서비스 인스턴스 필드인데
 * {@code clear()}는 (실제로는 호출되지 않는) {@code executeTest()}에만 있어,
 * 컨트롤러가 실제로 사용하는 {@code executeTestWithId()}를 연속 호출하면
 * 이전 실행의 에러 내역이 다음 실행 결과에 그대로 남는다.
 */
@ExtendWith(MockitoExtension.class)
class TestExecutionServiceTest {

    private static FakeTargetServer targetServer;

    @Mock
    private TestExecutionRepository executionRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    /** 실행마다 만들어진 워커 executor를 기록한다 (정리·격리 검증용) */
    private final List<ExecutorService> createdExecutors = new CopyOnWriteArrayList<>();

    private HttpClient httpClient;
    private PlatformThreadBudget platformBudget;
    private TestExecutionService service;

    @BeforeAll
    static void startServer() {
        targetServer = FakeTargetServer.start();
    }

    @AfterAll
    static void stopServer() {
        targetServer.close();
    }

    @BeforeEach
    void setUp() {
        createdExecutors.clear();
        httpClient = HttpClient.newHttpClient();

        // save() 호출 시 id를 채워 반환한다 (saveExecution()이 saved.getId()를 곧바로 읽으므로 필수).
        // 실행이 저장 단계까지 가지 못하는 테스트(워커 실패)도 있으므로 lenient로 둔다.
        lenient().when(executionRepository.save(any(TestExecution.class)))
                .thenAnswer(invocation -> {
                    TestExecution execution = invocation.getArgument(0);
                    execution.setId(1L);
                    return execution;
                });

        // 플랫폼 스레드 전역 상한은 4로 둔다 (동시 PLATFORM 실행의 합계 검증용)
        platformBudget = new PlatformThreadBudget(4);
        RunExecutorFactory recordingFactory = new RunExecutorFactory() {
            @Override
            public ExecutorService create(TestConfigDto.ThreadType threadType, String testId, int poolSize) {
                ExecutorService executor = super.create(threadType, testId, poolSize);
                createdExecutors.add(executor);
                return executor;
            }
        };
        service = new TestExecutionService(
                recordingFactory, platformBudget, httpClient, executionRepository, messagingTemplate);
    }

    @AfterEach
    void tearDown() {
        targetServer.respondWith(200); // 다음 테스트를 위해 기본값으로 되돌림
    }

    @Test
    void 연속된_두_번의_실행에서_이전_실행의_에러가_다음_결과에_섞이지_않는다() {
        // 1회차: 대상 서버가 전부 500을 반환 → 실패로 집계된다
        targetServer.respondWith(500);
        service.executeTestWithId("test-1", fixedCountConfig());

        // 2회차: 대상 서버가 전부 200을 반환 → 실패가 전혀 없어야 한다
        targetServer.respondWith(200);
        TestResultDto secondResult = service.executeTestWithId("test-2", fixedCountConfig());

        assertThat(secondResult.getFailCount())
                .as("2회차는 전부 성공했으므로 실패 건수는 0이어야 한다")
                .isZero();

        assertThat(secondResult.getErrorBreakdown())
                .as("2회차 결과에 1회차의 에러 내역(SERVER_ERROR_500)이 남아있으면 안 된다 (B1)")
                .isEmpty();
    }

    @Test
    void 동시에_실행된_두_테스트의_에러_내역이_서로_섞이지_않는다() throws Exception {
        // 컨트롤러는 실행을 비동기로 띄우므로 서로 다른 테스트가 같은 서비스에서 동시에 돈다.
        // A는 빨리 끝나며 500 에러를 남기고, B는 A보다 한참 늦게 끝나며 전부 성공한다.
        // 에러 집계가 실행별로 격리되지 않으면 B의 결과에 A의 에러가 묻어난다.
        try (FakeTargetServer failingServer = FakeTargetServer.start().respondWith(500, Duration.ofMillis(10));
             FakeTargetServer healthyServer = FakeTargetServer.start().respondWith(200, Duration.ofMillis(200))) {

            ExecutorService runners = Executors.newFixedThreadPool(2);
            try {
                CompletableFuture<TestResultDto> resultA = CompletableFuture.supplyAsync(
                        () -> service.executeTestWithId("test-A", fixedCountConfig(failingServer.url())), runners);
                CompletableFuture<TestResultDto> resultB = CompletableFuture.supplyAsync(
                        () -> service.executeTestWithId("test-B", fixedCountConfig(healthyServer.url())), runners);

                TestResultDto a = resultA.get(30, TimeUnit.SECONDS);
                TestResultDto b = resultB.get(30, TimeUnit.SECONDS);

                assertThat(a.getErrorBreakdown()).containsExactly(entry("SERVER_ERROR_500", 10));
                assertThat(b.getFailCount()).isZero();
                assertThat(b.getErrorBreakdown())
                        .as("동시에 실행된 A의 에러가 B의 결과에 섞이면 안 된다")
                        .isEmpty();
            } finally {
                runners.shutdownNow();
            }
        }
    }

    @Test
    void 실행이_끝나면_그_실행의_executor는_종료되어_있다() {
        service.executeTestWithId("test-lifecycle", fixedCountConfig());

        assertThat(createdExecutors).hasSize(1);
        assertThat(createdExecutors.get(0).isTerminated())
                .as("try-with-resources close()가 모든 워커의 완료를 기다린 뒤 executor를 종료해야 한다")
                .isTrue();
    }

    @Test
    void 실행마다_서로_다른_executor를_사용한다() {
        service.executeTestWithId("test-1", fixedCountConfig());
        service.executeTestWithId("test-2", fixedCountConfig());

        assertThat(createdExecutors).hasSize(2);
        assertThat(createdExecutors.get(0)).isNotSameAs(createdExecutors.get(1));
    }

    @Test
    void 플랫폼_스레드_타입으로도_실행되고_결과를_집계한다() {
        TestConfigDto platformConfig = TestConfigDto.builder()
                .url(targetServer.url())
                .threadType(TestConfigDto.ThreadType.PLATFORM)
                .virtualThreads(5)
                .requestsPerThread(2)
                .build();

        TestResultDto result = service.executeTestWithId("test-platform", platformConfig);

        assertThat(result.getThreadType()).isEqualTo("PLATFORM");
        assertThat(result.getSuccessCount()).isEqualTo(10);
        assertThat(result.getFailCount()).isZero();
        assertThat(createdExecutors).hasSize(1);
        assertThat(createdExecutors.get(0).isTerminated()).isTrue();
    }

    @Test
    void 동시에_실행된_PLATFORM_테스트들의_플랫폼_스레드_합계는_상한을_넘지_않는다() throws Exception {
        // 상한은 프로세스 전체 기준이다(이 테스트의 PlatformThreadBudget은 4). 실행마다 별도 풀을 만들면
        // 실행 2개가 겹칠 때 플랫폼 스레드가 8개까지 늘어난다.
        targetServer.respondWith(200, Duration.ofMillis(100));

        AtomicInteger peak = new AtomicInteger();
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread sampler = Thread.ofPlatform().daemon().start(() -> {
            while (sampling.get()) {
                long live = Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> t.getName().startsWith("pu-") && t.isAlive())
                        .count();
                peak.accumulateAndGet((int) live, Math::max);
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });

        ExecutorService runners = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<TestResultDto> a = CompletableFuture.supplyAsync(
                    () -> service.executeTestWithId("test-cap-A", platformConfig(4, 2)), runners);
            CompletableFuture<TestResultDto> b = CompletableFuture.supplyAsync(
                    () -> service.executeTestWithId("test-cap-B", platformConfig(4, 2)), runners);

            // 직렬화되어 늦게 시작하더라도 두 실행 모두 정확히 끝나야 한다
            assertThat(a.get(30, TimeUnit.SECONDS).getSuccessCount()).isEqualTo(8);
            assertThat(b.get(30, TimeUnit.SECONDS).getSuccessCount()).isEqualTo(8);
        } finally {
            sampling.set(false);
            sampler.join();
            runners.shutdownNow();
        }

        assertThat(peak.get()).as("샘플러가 플랫폼 스레드를 실제로 관측해야 한다").isGreaterThan(0);
        assertThat(peak.get())
                .as("동시에 실행된 PLATFORM 테스트들의 플랫폼 스레드 합계가 상한(4)을 넘으면 안 된다")
                .isLessThanOrEqualTo(4);
    }

    @Test
    void 워커가_예외로_비정상_종료하면_조용히_넘어가지_않고_실패로_드러난다() {
        // 첫 전송(시작 알림, 메인 스레드)은 통과시키고, 이후 워커가 보내는 진행 메트릭에서 예외를 던진다
        doNothing().doThrow(new IllegalStateException("broker down"))
                .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

        assertThatThrownBy(() -> service.executeTestWithId("test-worker-fail", fixedCountConfig()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test-worker-fail")
                .hasRootCauseMessage("broker down");
    }

    @Test
    void 요청_로깅이_꺼져_있으면_에러_응답이어도_요청_단위_로그를_남기지_않는다() {
        targetServer.respondWith(500);
        ListAppender<ILoggingEvent> logs = captureServiceLogs();

        try {
            TestResultDto result = service.executeTestWithId("test-log-off", fixedCountConfigWithLogging(false));

            assertThat(result.getErrorBreakdown()).containsEntry("SERVER_ERROR_500", 10);
            assertThat(requestLevelLogs(logs)).as("에러는 로그가 아니라 errorBreakdown으로 집계된다").isEmpty();
        } finally {
            releaseServiceLogs(logs);
        }
    }

    @Test
    void 요청_로깅이_켜져_있으면_에러_응답마다_로그를_남긴다() {
        targetServer.respondWith(500);
        ListAppender<ILoggingEvent> logs = captureServiceLogs();

        try {
            service.executeTestWithId("test-log-on", fixedCountConfigWithLogging(true));

            assertThat(requestLevelLogs(logs)).hasSize(10);
        } finally {
            releaseServiceLogs(logs);
        }
    }

    @Test
    void 요청_로깅_옵션을_지정하지_않으면_기본은_꺼짐이다() {
        targetServer.respondWith(500);
        ListAppender<ILoggingEvent> logs = captureServiceLogs();

        try {
            service.executeTestWithId("test-log-default", fixedCountConfig());

            assertThat(requestLevelLogs(logs)).isEmpty();
        } finally {
            releaseServiceLogs(logs);
        }
    }

    @Test
    void PLATFORM_실행이_정상_종료하면_예산을_전부_반환한다() {
        service.executeTestWithId("test-budget-ok", platformConfig(3, 2));

        assertThat(platformBudget.availablePermits()).isEqualTo(4);
    }

    @Test
    void 워커가_실패해도_PLATFORM_예산은_반환된다() {
        doNothing().doThrow(new IllegalStateException("broker down"))
                .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

        assertThatThrownBy(() -> service.executeTestWithId("test-budget-fail", platformConfig(3, 2)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(platformBudget.availablePermits())
                .as("실패한 실행이 permit을 붙잡고 있으면 이후 PLATFORM 실행이 영원히 대기한다")
                .isEqualTo(4);
    }

    @Test
    void VIRTUAL_실행은_플랫폼_예산을_사용하지_않는다() {
        service.executeTestWithId("test-budget-virtual", fixedCountConfig());

        assertThat(platformBudget.availablePermits()).isEqualTo(4);
    }

    /** 실행 스레드를 도중에 인터럽트한다. 결과 또는 던져진 예외를 돌려준다. */
    private record InterruptedRun(Throwable thrown, TestResultDto result) {
    }

    private InterruptedRun runAndInterrupt(String testId, TestConfigDto config) throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<TestResultDto> result = new AtomicReference<>();
        Thread runner = Thread.ofPlatform().start(() -> {
            try {
                result.set(service.executeTestWithId(testId, config));
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        Thread.sleep(150); // 첫 요청들이 대상 서버 응답을 기다리는 도중
        runner.interrupt();
        runner.join(20_000);
        assertThat(runner.isAlive()).as("인터럽트 후에도 실행이 끝나야 한다").isFalse();
        return new InterruptedRun(thrown.get(), result.get());
    }

    private void assertNotReportedAsCompleted(String testId) {
        verify(executionRepository, never()).save(any(TestExecution.class));
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/test-complete/" + testId), any(Object.class));
    }

    @Test
    void PLATFORM_실행이_도중에_인터럽트되면_일부만_수행된_결과를_저장하거나_완료로_전송하지_않는다() throws Exception {
        // 워커 10개, 풀 4개 → 6개는 큐에서 대기. shutdownNow()가 큐의 작업을 버려도 완료로 처리하면 안 된다.
        targetServer.respondWith(200, Duration.ofMillis(300));

        InterruptedRun run = runAndInterrupt("test-int-platform", platformConfig(10, 5));

        assertThat(run.thrown()).as("일부만 수행된 실행은 예외로 끝나야 한다").isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test-int-platform").hasMessageContaining("중단");
        assertThat(run.result()).isNull();
        assertNotReportedAsCompleted("test-int-platform");
        assertThat(platformBudget.availablePermits()).as("중단되어도 예산은 반환되어야 한다").isEqualTo(4);
    }

    @Test
    void VIRTUAL_실행이_도중에_인터럽트되면_일부만_수행된_결과를_저장하거나_완료로_전송하지_않는다() throws Exception {
        targetServer.respondWith(200, Duration.ofMillis(300));
        TestConfigDto config = TestConfigDto.builder()
                .url(targetServer.url())
                .threadType(TestConfigDto.ThreadType.VIRTUAL)
                .virtualThreads(10)
                .requestsPerThread(5)
                .build();

        targetServer.resetReceivedCount();
        InterruptedRun run = runAndInterrupt("test-int-virtual", config);

        assertThat(run.thrown()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test-int-virtual").hasMessageContaining("중단");
        assertThat(run.result()).isNull();
        assertNotReportedAsCompleted("test-int-virtual");
        // 중단 요청을 받은 뒤에는 남은 요청(워커당 4개, 총 40개)을 대상 서버로 더 보내지 않아야 한다.
        // 첫 요청 10개는 이미 나간 상태이므로 그 이하여야 한다.
        assertThat(targetServer.receivedCount())
                .as("취소 후에도 대상 서버로 요청을 계속 보내면 안 된다")
                .isLessThanOrEqualTo(10);
    }

    @Test
    void 인터럽트된_요청은_실패_통계에_기록하지_않고_인터럽트_상태를_유지한다() {
        TestRunContext ctx = new TestRunContext("test-int-request", fixedCountConfig());

        Thread.currentThread().interrupt();
        boolean stillInterrupted;
        try {
            service.executeRequest(ctx, 0, 0);
        } finally {
            stillInterrupted = Thread.interrupted(); // 확인하면서 다음 테스트를 위해 플래그를 지운다
        }

        assertThat(stillInterrupted).as("인터럽트 플래그를 복원해야 상위에서 중단을 알 수 있다").isTrue();
        assertThat(ctx.errorBreakdown()).as("인터럽트는 대상 서버의 실패가 아니다").isEmpty();
        assertThat(ctx.failCount()).isZero();
        assertThat(ctx.completedCount()).isZero();
    }

    @Test
    void 성공_응답은_successCount와_tps에_정확히_반영된다() {
        // 응답에 지연을 줘 총 소요시간이 0ms로 반올림되지 않게 한다 (tps 계산 검증용)
        targetServer.respondWith(200, Duration.ofMillis(5));

        TestResultDto result = service.executeTestWithId("test-success", fixedCountConfig());

        assertThat(result.getSuccessCount()).isEqualTo(10);
        assertThat(result.getFailCount()).isZero();
        assertThat(result.getTotalDurationMs()).isGreaterThan(0);
        assertThat(result.getTps()).isGreaterThan(0);
    }

    @Test
    void 클라이언트_에러는_상태코드별로_분류된다() {
        targetServer.respondWith(404);

        TestResultDto result = service.executeTestWithId("test-404", fixedCountConfig());

        assertThat(result.getFailCount()).isEqualTo(10);
        assertThat(result.getErrorBreakdown()).containsEntry("CLIENT_ERROR_404", 10);
    }

    @Test
    void 서버_에러는_상태코드별로_분류된다() {
        targetServer.respondWith(503);

        TestResultDto result = service.executeTestWithId("test-503", fixedCountConfig());

        assertThat(result.getFailCount()).isEqualTo(10);
        assertThat(result.getErrorBreakdown()).containsEntry("SERVER_ERROR_503", 10);
    }

    private static ch.qos.logback.classic.Logger serviceLogger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TestExecutionService.class);
    }

    private ListAppender<ILoggingEvent> captureServiceLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLogger().addAppender(appender);
        return appender;
    }

    private void releaseServiceLogs(ListAppender<ILoggingEvent> appender) {
        serviceLogger().detachAppender(appender);
        appender.stop();
    }

    /** 요청 하나당 남는 로그(WARN 이상). 시작/완료 요약(INFO)은 제외한다. */
    private static List<ILoggingEvent> requestLevelLogs(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).toList();
    }

    private TestConfigDto fixedCountConfigWithLogging(boolean enableLogging) {
        return TestConfigDto.builder()
                .url(targetServer.url())
                .threadType(TestConfigDto.ThreadType.VIRTUAL)
                .virtualThreads(5)
                .requestsPerThread(2)
                .enableLogging(enableLogging)
                .build();
    }

    private TestConfigDto fixedCountConfig() {
        return fixedCountConfig(targetServer.url());
    }

    private TestConfigDto platformConfig(int workers, int requestsPerWorker) {
        return TestConfigDto.builder()
                .url(targetServer.url())
                .threadType(TestConfigDto.ThreadType.PLATFORM)
                .virtualThreads(workers)
                .requestsPerThread(requestsPerWorker)
                .build();
    }

    private TestConfigDto fixedCountConfig(String url) {
        return TestConfigDto.builder()
                .url(url)
                .threadType(TestConfigDto.ThreadType.VIRTUAL)
                .virtualThreads(5)
                .requestsPerThread(2)
                .build();
    }
}
