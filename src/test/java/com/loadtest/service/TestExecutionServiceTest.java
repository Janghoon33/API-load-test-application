package com.loadtest.service;

import com.loadtest.dto.RealtimeMetricDto;
import com.loadtest.dto.TestConfigDto;
import com.loadtest.dto.TestResultDto;
import com.loadtest.entity.TestExecution;
import com.loadtest.monitor.JvmMetricsProbe;
import com.loadtest.monitor.PinningMonitor;
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
import java.util.concurrent.ScheduledExecutorService;
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
    private ScheduledExecutorService samplerScheduler;
    /** 꺼진 모니터: 실행 종료 시 전달 확정을 기다리지 않으므로 기존 테스트의 소요 시간이 늘지 않는다 */
    private final PinningMonitor offMonitor = new PinningMonitor(false);

    private static final Duration SAMPLE_INTERVAL = Duration.ofMillis(100);

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
        samplerScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("metric-sampler-test").factory());
        recordingFactoryRef = recordingFactory;
        service = new TestExecutionService(
                recordingFactory, platformBudget, httpClient, executionRepository, messagingTemplate,
                new JvmMetricsProbe(offMonitor), offMonitor, samplerScheduler, SAMPLE_INTERVAL, SAMPLE_INTERVAL);
    }

    @AfterEach
    void tearDown() {
        samplerScheduler.shutdownNow();
        // 다음 테스트를 위해 기본값으로 되돌림. 상태코드만 바꾸는 respondWith(200)은 앞 테스트의 지연을 남기므로 지연도 함께 초기화한다.
        targetServer.respondWith(200, Duration.ZERO);
    }

    private RunExecutorFactory recordingFactoryRef;

    /** 워커가 요청을 수행하다 예외로 비정상 종료하는 서비스 (요청 실행 단계가 깨진 경우를 흉내 낸다) */
    private TestExecutionService serviceWithFailingWorkers() {
        return serviceWithFailingWorkers(offMonitor);
    }

    private TestExecutionService serviceWithFailingWorkers(PinningMonitor monitor) {
        return new TestExecutionService(
                recordingFactoryRef, platformBudget, httpClient, executionRepository, messagingTemplate,
                new JvmMetricsProbe(monitor), monitor, samplerScheduler, SAMPLE_INTERVAL, SAMPLE_INTERVAL) {
            @Override
            void executeRequest(TestRunContext ctx, int threadId, int requestId) {
                throw new IllegalStateException("worker down");
            }
        };
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

    /** /topic/metrics/{testId}로 나간 전송을 (전송한 스레드 이름, 상태)로 기록한다 */
    private record MetricSend(String threadName, String status) {
    }

    private List<MetricSend> captureMetricSends(String testId) {
        List<MetricSend> sends = new CopyOnWriteArrayList<>();
        lenient().doAnswer(invocation -> {
            RealtimeMetricDto dto = invocation.getArgument(1);
            sends.add(new MetricSend(Thread.currentThread().getName(), dto.getStatus()));
            return null;
        }).when(messagingTemplate).convertAndSend(eq("/topic/metrics/" + testId), any(Object.class));
        return sends;
    }

    @Test
    void 메트릭_전송은_워커_스레드가_아니라_샘플러_스레드가_한다() {
        // 워커당 20요청 × 30ms ≈ 600ms → 100ms 주기로 여러 번 샘플링된다
        targetServer.respondWith(200, Duration.ofMillis(30));
        List<MetricSend> sends = captureMetricSends("test-sampler-thread");

        service.executeTestWithId("test-sampler-thread", TestConfigDto.builder()
                .url(targetServer.url())
                .threadType(TestConfigDto.ThreadType.VIRTUAL)
                .virtualThreads(5)
                .requestsPerThread(20)
                .build());

        // B4: 예전에는 워커가 completed % 100 조건을 직접 평가해 전송했다
        assertThat(sends).extracting(MetricSend::threadName)
                .as("워커(vu-/pu-)는 메트릭을 전송하지 않는다")
                .noneMatch(name -> name.startsWith("vu-") || name.startsWith("pu-"));
        assertThat(sends).extracting(MetricSend::threadName)
                .as("주기 전송은 샘플러 스레드가 한다")
                .contains("metric-sampler-test");
        assertThat(sends.stream().filter(s -> s.status().equals("RUNNING")).count())
                .as("시작 알림 1건 + 샘플 여러 건")
                .isGreaterThanOrEqualTo(3);
        assertThat(sends.get(sends.size() - 1).status()).isEqualTo("COMPLETED");
    }

    @Test
    void 전송_횟수는_완료_건수가_아니라_실행_시간에_비례한다() {
        // 요청 200건이 순식간에 끝나는 실행. 예전 방식이면 completed % 100 때문에 건수에 비례해 전송된다.
        List<MetricSend> sends = captureMetricSends("test-send-count");

        service.executeTestWithId("test-send-count", TestConfigDto.builder()
                .url(targetServer.url())
                .threadType(TestConfigDto.ThreadType.VIRTUAL)
                .virtualThreads(20)
                .requestsPerThread(10)
                .build());

        // 시작 1 + 완료 1 + (실행 시간 / 100ms)번의 샘플. 실행이 1초 미만이므로 여유를 두고 상한만 확인한다.
        assertThat(sends.size()).isLessThan(15);
    }

    @Test
    void 지연이_있는_대상에_대한_결과_백분위는_그_지연_근처다() {
        targetServer.respondWith(200, Duration.ofMillis(50));

        TestResultDto result = service.executeTestWithId("test-percentile", fixedCountConfig());

        assertThat(result.getP50Ms()).isBetween(45.0, 200.0);
        assertThat(result.getP99Ms()).isGreaterThanOrEqualTo(result.getP50Ms());
        assertThat(result.getP999Ms()).isGreaterThanOrEqualTo(result.getP99Ms());
    }

    @Test
    void 실시간_메트릭에_평균_백분위와_런타임_지표가_실제_값으로_담긴다() {
        targetServer.respondWith(200, Duration.ofMillis(30));
        List<RealtimeMetricDto> metrics = new CopyOnWriteArrayList<>();
        lenient().doAnswer(invocation -> {
            metrics.add(invocation.getArgument(1));
            return null;
        }).when(messagingTemplate).convertAndSend(eq("/topic/metrics/test-realtime-fields"), any(Object.class));

        service.executeTestWithId("test-realtime-fields", TestConfigDto.builder()
                .url(targetServer.url())
                .threadType(TestConfigDto.ThreadType.VIRTUAL)
                .virtualThreads(5)
                .requestsPerThread(20)
                .build());

        RealtimeMetricDto completed = metrics.get(metrics.size() - 1);
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(metrics).as("실행 도중 샘플에는 평균 응답시간이 0이 아닌 값으로 채워진다")
                .anyMatch(m -> m.getStatus().equals("RUNNING") && m.getAvgResponseTimeMs() >= 25);
        assertThat(completed.getP95Ms()).isGreaterThanOrEqualTo(25.0);
        assertThat(completed.getP99Ms()).isGreaterThanOrEqualTo(completed.getP95Ms());
        assertThat(completed.getHeapUsedBytes()).isPositive();
        assertThat(completed.getPlatformThreadCount()).isPositive();
        assertThat(completed.getCarrierParallelism()).isPositive();
        assertThat(metrics).as("실행 도중에는 활성 워커가 관측된다")
                .anyMatch(m -> m.getStatus().equals("RUNNING") && m.getActiveWorkers() > 0);
        assertThat(completed.getActiveWorkers()).as("끝난 뒤에는 활성 워커가 없다").isZero();
    }

    @Test
    void 결과와_저장되는_엔티티에_백분위와_런타임_지표가_담긴다() {
        targetServer.respondWith(200, Duration.ofMillis(20));

        TestResultDto result = service.executeTestWithId("test-result-fields", fixedCountConfig());

        assertThat(result.getP50Ms()).isNotNull();
        assertThat(result.getPeakHeapBytes()).isPositive();
        assertThat(result.getPeakPlatformThreads()).isPositive();
        assertThat(result.getGcCount()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(result.getGcTimeMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(result.getPinnedCount()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(result.getPinnedTimeMs()).isNotNull().isGreaterThanOrEqualTo(0);

        org.mockito.ArgumentCaptor<TestExecution> saved = org.mockito.ArgumentCaptor.forClass(TestExecution.class);
        verify(executionRepository).save(saved.capture());
        TestExecution entity = saved.getValue();
        assertThat(entity.getP50Ms()).isEqualTo(result.getP50Ms());
        assertThat(entity.getP90Ms()).isEqualTo(result.getP90Ms());
        assertThat(entity.getP95Ms()).isEqualTo(result.getP95Ms());
        assertThat(entity.getP99Ms()).isEqualTo(result.getP99Ms());
        assertThat(entity.getP999Ms()).isEqualTo(result.getP999Ms());
        assertThat(entity.getPeakHeapBytes()).isEqualTo(result.getPeakHeapBytes());
        assertThat(entity.getPeakPlatformThreads()).isEqualTo(result.getPeakPlatformThreads());
        assertThat(entity.getGcCount()).isEqualTo(result.getGcCount());
        assertThat(entity.getGcTimeMs()).isEqualTo(result.getGcTimeMs());
        assertThat(entity.getPinnedCount()).isEqualTo(result.getPinnedCount());
        assertThat(entity.getPinnedTimeMs()).isEqualTo(result.getPinnedTimeMs());
    }

    @Test
    void 저장된_이력을_조회하면_백분위와_런타임_지표가_복원된다() {
        TestExecution entity = TestExecution.builder()
                .id(7L).url("http://x").threadType("VIRTUAL")
                .avgResponseTimeMs(5L).minResponseTimeMs(1L).maxResponseTimeMs(9L).totalDurationMs(100L).tps(10.0)
                .totalRequests(10).successCount(10).failCount(0)
                .p50Ms(12.5).p90Ms(20.0).p95Ms(25.0).p99Ms(30.0).p999Ms(31.0)
                .peakHeapBytes(1000L).peakPlatformThreads(40)
                .gcCount(2L).gcTimeMs(15L).pinnedCount(1L).pinnedTimeMs(3L)
                .build();
        org.mockito.Mockito.when(executionRepository.findById(7L)).thenReturn(java.util.Optional.of(entity));

        TestResultDto dto = service.getExecution(7L);

        assertThat(dto.getP50Ms()).isEqualTo(12.5);
        assertThat(dto.getP999Ms()).isEqualTo(31.0);
        assertThat(dto.getPeakHeapBytes()).isEqualTo(1000L);
        assertThat(dto.getPeakPlatformThreads()).isEqualTo(40);
        assertThat(dto.getGcCount()).isEqualTo(2L);
        assertThat(dto.getPinnedTimeMs()).isEqualTo(3L);
    }

    @Test
    void 백분위_도입_이전에_저장된_이력은_해당_값이_null이다() {
        TestExecution legacy = TestExecution.builder()
                .id(8L).url("http://old").threadType("VIRTUAL")
                .avgResponseTimeMs(5L).minResponseTimeMs(1L).maxResponseTimeMs(9L).totalDurationMs(100L).tps(10.0)
                .totalRequests(10).successCount(10).failCount(0).build();
        org.mockito.Mockito.when(executionRepository.findById(8L)).thenReturn(java.util.Optional.of(legacy));

        TestResultDto dto = service.getExecution(8L);

        assertThat(dto.getP50Ms()).isNull();
        assertThat(dto.getPeakHeapBytes()).isNull();
        assertThat(dto.getGcCount()).isNull();
    }

    /** read() 호출 수를 세고, 플랫폼 스레드 수를 "이름이 prefix로 시작하는 살아 있는 스레드 수"로 대체하는 프로브 */
    private static JvmMetricsProbe probeCountingThreads(String prefix, AtomicInteger reads) {
        return new JvmMetricsProbe(new PinningMonitor(false)) {
            @Override
            public Reading read() {
                reads.incrementAndGet();
                long alive = Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> t.isAlive() && t.getName().startsWith(prefix))
                        .count();
                Reading real = super.read();
                return new Reading(real.heapUsedBytes(), (int) alive, real.gcCount(), real.gcTimeMs(),
                        real.carrierParallelism(), real.pinnedCount(), real.pinnedTimeMs());
            }
        };
    }

    /** read() 호출 수만 세는 가벼운 프로브 (주기 관측 횟수를 시간에 덜 민감하게 검증하기 위함) */
    private static JvmMetricsProbe probeCountingReads(AtomicInteger reads) {
        return new JvmMetricsProbe(new PinningMonitor(false)) {
            @Override
            public Reading read() {
                reads.incrementAndGet();
                return super.read();
            }
        };
    }

    private TestExecutionService serviceWith(JvmMetricsProbe probe, Duration tick, Duration peak) {
        return new TestExecutionService(
                recordingFactoryRef, platformBudget, httpClient, executionRepository, messagingTemplate,
                probe, offMonitor, samplerScheduler, tick, peak);
    }

    @Test
    void 주기_샘플보다_짧게_끝나는_PLATFORM_실행도_실행_중의_플랫폼_스레드_피크를_기록한다() {
        // 틱과 피크 관측기를 모두 꺼서(1시간), "워커 기동 직후 관측"만으로 피크가 잡히는지 확인한다.
        // 풀 스레드(pu-)는 실행이 끝나면 사라지므로 시작·종료 시점의 값만 보면 0으로 기록된다.
        targetServer.respondWith(200, Duration.ofMillis(100));
        TestExecutionService quick = serviceWith(
                probeCountingThreads("pu-test-peak-platform-", new AtomicInteger()),
                Duration.ofHours(1), Duration.ofHours(1));

        TestResultDto result = quick.executeTestWithId("test-peak-platform", platformConfig(10, 1));

        assertThat(result.getPeakPlatformThreads())
                .as("풀 크기(예산 4)만큼의 워커 스레드가 실행 중에 관측되어야 한다")
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    void 피크_관측기는_실행_중_주기적으로_읽고_실행이_끝나면_멈춘다() throws Exception {
        targetServer.respondWith(200, Duration.ofMillis(300));
        AtomicInteger reads = new AtomicInteger();
        TestExecutionService observed = serviceWith(
                probeCountingReads(reads),
                Duration.ofHours(1), Duration.ofMillis(20));

        observed.executeTestWithId("test-peak-observer", fixedCountConfig());

        // 기준선 1 + 시작 샘플 1 + 기동 직후 1 + 최종 1 외에, 600ms 이상 도는 동안 20ms 주기 관측이 여러 번 있어야 한다
        assertThat(reads.get()).as("피크 관측기가 주기적으로 읽는다").isGreaterThanOrEqualTo(10);

        Thread.sleep(60); // 종료 직전에 이미 시작된 관측이 끝나도록 잠깐 기다린다
        int settled = reads.get();
        Thread.sleep(150);
        assertThat(reads.get()).as("끝난 실행의 관측기가 계속 읽으면 안 된다").isEqualTo(settled);
    }

    /** JFR 없이 "전달 확정 전에는 핀닝이 0건, 확정 후에는 3건"을 흉내 내는 모니터 */
    private static class FakePinningMonitor extends PinningMonitor {
        private final boolean running;
        final AtomicBoolean delivered = new AtomicBoolean();
        final AtomicInteger awaitCalls = new AtomicInteger();
        volatile Runnable onAwait = () -> { };
        volatile boolean confirm = true;

        FakePinningMonitor(boolean running) {
            super(false);
            this.running = running;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public boolean awaitDelivery() {
            awaitCalls.incrementAndGet();
            onAwait.run();
            delivered.set(true); // 대기가 끝나야 지연된 이벤트가 도착한다
            return confirm;
        }

        @Override
        public Snapshot snapshot() {
            return delivered.get()
                    ? new Snapshot(3, 30, java.util.Map.of())
                    : new Snapshot(0, 0, java.util.Map.of());
        }
    }

    private TestExecutionService serviceWithMonitor(FakePinningMonitor monitor) {
        return new TestExecutionService(
                recordingFactoryRef, platformBudget, httpClient, executionRepository, messagingTemplate,
                new JvmMetricsProbe(monitor), monitor, samplerScheduler, SAMPLE_INTERVAL, SAMPLE_INTERVAL);
    }

    @Test
    void 최종_결과의_핀닝은_이벤트_전달을_확정한_뒤의_값이다() {
        FakePinningMonitor monitor = new FakePinningMonitor(true);

        TestResultDto result = serviceWithMonitor(monitor).executeTestWithId("test-pin-settle", fixedCountConfig());

        // 확정 전에 최종 스냅샷을 만들었다면 0으로 저장된다 (마지막 구간의 핀닝이 영구히 누락)
        assertThat(result.getPinnedCount()).isEqualTo(3);
        assertThat(result.getPinnedTimeMs()).isEqualTo(30);
        org.mockito.ArgumentCaptor<TestExecution> saved = org.mockito.ArgumentCaptor.forClass(TestExecution.class);
        verify(executionRepository).save(saved.capture());
        assertThat(saved.getValue().getPinnedCount()).isEqualTo(3);
    }

    @Test
    void 전달_확정_대기는_PLATFORM_예산을_반환한_뒤에_한다() {
        FakePinningMonitor monitor = new FakePinningMonitor(true);
        AtomicInteger permitsDuringWait = new AtomicInteger(-1);
        monitor.onAwait = () -> permitsDuringWait.set(platformBudget.availablePermits());

        serviceWithMonitor(monitor).executeTestWithId("test-pin-permits", platformConfig(3, 2));

        assertThat(permitsDuringWait.get())
                .as("대기 중에 예산을 쥐고 있으면 뒤따르는 PLATFORM 실행이 불필요하게 막힌다")
                .isEqualTo(4);
    }

    @Test
    void 전달_확정_대기_시간은_실행_시간에_섞이지_않는다() {
        FakePinningMonitor monitor = new FakePinningMonitor(true);
        monitor.onAwait = () -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        TestResultDto result = serviceWithMonitor(monitor).executeTestWithId("test-pin-duration", fixedCountConfig());

        assertThat(result.getTotalDurationMs())
                .as("대기가 소요 시간·TPS에 섞이면 부하 도구의 측정값이 틀어진다")
                .isLessThan(400);
        assertThat(result.getCompletedAt()).isNotNull();
    }

    @Test
    void 완료_메트릭의_경과_시간과_순간_TPS에도_대기_시간이_섞이지_않는다() {
        FakePinningMonitor monitor = new FakePinningMonitor(true);
        monitor.onAwait = () -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        java.util.concurrent.atomic.AtomicReference<RealtimeMetricDto> completed = new java.util.concurrent.atomic.AtomicReference<>();
        lenient().doAnswer(invocation -> {
            RealtimeMetricDto dto = invocation.getArgument(1);
            if ("COMPLETED".equals(dto.getStatus())) {
                completed.set(dto);
            }
            return null;
        }).when(messagingTemplate).convertAndSend(eq("/topic/metrics/test-pin-elapsed"), any(Object.class));

        serviceWithMonitor(monitor).executeTestWithId("test-pin-elapsed", fixedCountConfig());

        assertThat(completed.get().getElapsedTimeMs())
                .as("COMPLETED 메트릭의 경과 시간은 워커가 끝난 시각 기준이어야 한다")
                .isLessThan(400);
    }

    @Test
    void 최종_결과의_GC_힙_스레드는_워커_종료_시점_값이고_핀닝만_확정_후_값이다() {
        FakePinningMonitor monitor = new FakePinningMonitor(true);
        AtomicBoolean waited = new AtomicBoolean();
        monitor.onAwait = () -> waited.set(true);
        // 실제 JVM 값은 실행 중에도 흔들리므로, 대기 전/후 값을 고정한 프로브로 결정적으로 검증한다.
        // 대기 이후에는 GC·힙·스레드가 크게 달라진 것으로 흉내 낸다.
        JvmMetricsProbe probe = new JvmMetricsProbe(monitor) {
            @Override
            public Reading read() {
                PinningMonitor.Snapshot pin = monitor.snapshot();
                boolean late = waited.get();
                return new Reading(late ? 999_999L : 1_000L, late ? 210 : 10, late ? 8 : 3, late ? 80 : 30,
                        4, pin.count(), pin.totalMillis());
            }
        };
        TestExecutionService svc = new TestExecutionService(
                recordingFactoryRef, platformBudget, httpClient, executionRepository, messagingTemplate,
                probe, monitor, samplerScheduler, SAMPLE_INTERVAL, SAMPLE_INTERVAL);
        java.util.concurrent.atomic.AtomicReference<RealtimeMetricDto> completed = new java.util.concurrent.atomic.AtomicReference<>();
        lenient().doAnswer(invocation -> {
            RealtimeMetricDto dto = invocation.getArgument(1);
            if ("COMPLETED".equals(dto.getStatus())) {
                completed.set(dto);
            }
            return null;
        }).when(messagingTemplate).convertAndSend(eq("/topic/metrics/test-final-runtime"), any(Object.class));

        TestResultDto result = svc.executeTestWithId("test-final-runtime", fixedCountConfig());

        assertThat(result.getGcCount()).as("대기 중 발생한 GC가 이 실행의 몫으로 섞이면 안 된다").isZero();
        assertThat(result.getGcTimeMs()).isZero();
        assertThat(result.getPeakHeapBytes()).isEqualTo(1_000L);
        assertThat(result.getPeakPlatformThreads()).isEqualTo(10);
        assertThat(result.getPinnedCount()).as("핀닝만 전달 확정 후의 값").isEqualTo(3);
        assertThat(completed.get().getHeapUsedBytes()).isEqualTo(1_000L);
        assertThat(completed.get().getPlatformThreadCount()).isEqualTo(10);
    }

    @Test
    void 꺼진_모니터는_전달_확정을_기다리지_않는다() {
        FakePinningMonitor monitor = new FakePinningMonitor(false);

        TestResultDto result = serviceWithMonitor(monitor).executeTestWithId("test-pin-off", fixedCountConfig());

        assertThat(monitor.awaitCalls.get()).isZero();
        assertThat(result.getPinnedCount()).isZero();
    }

    @Test
    void 전달_확정이_제한_시간을_넘겨도_결과는_정상_저장되고_전송된다() {
        FakePinningMonitor monitor = new FakePinningMonitor(true);
        monitor.confirm = false;

        TestResultDto result = serviceWithMonitor(monitor).executeTestWithId("test-pin-timeout", fixedCountConfig());

        assertThat(result.getTotalRequests()).isEqualTo(10);
        verify(executionRepository).save(any(TestExecution.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/test-complete/test-pin-timeout"), any(Object.class));
    }

    @Test
    void COMPLETED는_전달_확정_뒤에_전송되고_그_사이에_RUNNING이_끼지_않는다() {
        FakePinningMonitor monitor = new FakePinningMonitor(true);
        List<String> events = new CopyOnWriteArrayList<>();
        monitor.onAwait = () -> events.add("AWAIT");
        lenient().doAnswer(invocation -> {
            RealtimeMetricDto dto = invocation.getArgument(1);
            events.add(dto.getStatus());
            return null;
        }).when(messagingTemplate).convertAndSend(eq("/topic/metrics/test-pin-order"), any(Object.class));

        serviceWithMonitor(monitor).executeTestWithId("test-pin-order", fixedCountConfig());

        int await = events.indexOf("AWAIT");
        assertThat(await).as("전달 확정 대기가 있어야 한다").isGreaterThanOrEqualTo(0);
        assertThat(events.subList(await + 1, events.size()))
                .as("대기 이후에는 COMPLETED 하나만 온다")
                .containsExactly("COMPLETED");
    }

    /** 같은 인스턴스를 유지한 채 요청 실행 단계의 실패를 켜고 끌 수 있는 서비스 (실행 간 상태 공유 검증용) */
    private TestExecutionService serviceFailingWhen(FakePinningMonitor monitor, AtomicBoolean failNow) {
        return new TestExecutionService(
                recordingFactoryRef, platformBudget, httpClient, executionRepository, messagingTemplate,
                new JvmMetricsProbe(monitor), monitor, samplerScheduler, SAMPLE_INTERVAL, SAMPLE_INTERVAL) {
            @Override
            void executeRequest(TestRunContext ctx, int threadId, int requestId) {
                if (failNow.get()) {
                    throw new IllegalStateException("worker down");
                }
                super.executeRequest(ctx, threadId, requestId);
            }
        };
    }

    @Test
    void 확정_없이_끝난_실행의_지연_핀닝이_다음_실행에_귀속되지_않는다() {
        FakePinningMonitor monitor = new FakePinningMonitor(true);
        AtomicBoolean failNow = new AtomicBoolean(true);
        TestExecutionService svc = serviceFailingWhen(monitor, failNow);

        // 1회차: 워커가 실패해 전달 확정 없이 끝난다 → 이 실행의 지연 이벤트(3건)가 아직 도착하지 않은 상태
        assertThatThrownBy(() -> svc.executeTestWithId("test-leak-1", fixedCountConfig()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(monitor.awaitCalls.get()).isZero();

        failNow.set(false);
        TestResultDto second = svc.executeTestWithId("test-leak-2", fixedCountConfig());

        // 시작 전에 확정하지 않으면 기준선은 0, 종료 확정 후 값은 3이라 1회차의 핀닝이 2회차의 것으로 기록된다
        assertThat(second.getPinnedCount()).as("1회차의 핀닝이 2회차에 섞이면 안 된다").isZero();
        assertThat(second.getPinnedTimeMs()).isZero();
    }

    @Test
    void 정상_종료한_실행_뒤에는_시작_전_전달_확정을_기다리지_않는다() {
        FakePinningMonitor monitor = new FakePinningMonitor(true);
        TestExecutionService svc = serviceWithMonitor(monitor);

        svc.executeTestWithId("test-clean-1", fixedCountConfig());
        svc.executeTestWithId("test-clean-2", fixedCountConfig());

        assertThat(monitor.awaitCalls.get())
                .as("실행마다 종료 시점에 한 번씩만 기다린다. 정상 흐름에 지연을 더하면 안 된다")
                .isEqualTo(2);
    }

    @Test
    void 전달_확정이_시간_초과된_실행_뒤에는_다음_실행이_시작_전에_다시_확정한다() {
        FakePinningMonitor monitor = new FakePinningMonitor(true);
        monitor.confirm = false;
        TestExecutionService svc = serviceWithMonitor(monitor);

        svc.executeTestWithId("test-timeout-1", fixedCountConfig());   // 종료 시 1회 (확정 실패)
        assertThat(monitor.awaitCalls.get()).isEqualTo(1);
        svc.executeTestWithId("test-timeout-2", fixedCountConfig());   // 시작 전 1회 + 종료 시 1회

        assertThat(monitor.awaitCalls.get()).isEqualTo(3);
    }

    @Test
    void 시작_전_전달_확정은_시작_시각_이전이고_소요_시간에_섞이지_않는다() {
        FakePinningMonitor monitor = new FakePinningMonitor(true);
        AtomicBoolean failNow = new AtomicBoolean(true);
        TestExecutionService svc = serviceFailingWhen(monitor, failNow);
        assertThatThrownBy(() -> svc.executeTestWithId("test-start-wait-1", fixedCountConfig()))
                .isInstanceOf(IllegalStateException.class);

        java.util.concurrent.atomic.AtomicReference<java.time.LocalDateTime> firstWaitDone =
                new java.util.concurrent.atomic.AtomicReference<>();
        monitor.onAwait = () -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            firstWaitDone.compareAndSet(null, java.time.LocalDateTime.now());
        };
        failNow.set(false);

        TestResultDto result = svc.executeTestWithId("test-start-wait-2", fixedCountConfig());

        assertThat(result.getStartedAt()).as("시작 시각은 시작 전 확정이 끝난 뒤여야 한다")
                .isAfterOrEqualTo(firstWaitDone.get());
        assertThat(result.getTotalDurationMs()).as("시작 전 대기가 소요 시간·TPS에 섞이면 안 된다").isLessThan(300);
    }

    @Test
    void 시작_전_전달_확정은_PLATFORM_예산을_획득한_뒤에_한다() {
        FakePinningMonitor monitor = new FakePinningMonitor(true);
        AtomicBoolean failNow = new AtomicBoolean(true);
        TestExecutionService svc = serviceFailingWhen(monitor, failNow);
        assertThatThrownBy(() -> svc.executeTestWithId("test-start-permits-1", platformConfig(3, 2)))
                .isInstanceOf(IllegalStateException.class);

        List<Integer> permitsAtAwait = new CopyOnWriteArrayList<>();
        monitor.onAwait = () -> permitsAtAwait.add(platformBudget.availablePermits());
        failNow.set(false);

        svc.executeTestWithId("test-start-permits-2", platformConfig(3, 2));

        // [시작 전 확정, 종료 시 확정]. 시작 전 확정은 예산을 이미 획득한 뒤(예산 미획득 시점이면 4)여야 한다.
        assertThat(permitsAtAwait).hasSize(2);
        assertThat(permitsAtAwait.get(0)).as("시작 전 확정은 예산 획득 이후").isLessThan(4);
        assertThat(permitsAtAwait.get(1)).as("종료 시 확정은 예산 반환 이후").isEqualTo(4);
    }

    @Test
    void 꺼진_모니터는_실패한_실행_뒤에도_기다리지_않는다() {
        FakePinningMonitor monitor = new FakePinningMonitor(false);
        AtomicBoolean failNow = new AtomicBoolean(true);
        TestExecutionService svc = serviceFailingWhen(monitor, failNow);
        assertThatThrownBy(() -> svc.executeTestWithId("test-off-1", fixedCountConfig()))
                .isInstanceOf(IllegalStateException.class);
        failNow.set(false);

        svc.executeTestWithId("test-off-2", fixedCountConfig());

        assertThat(monitor.awaitCalls.get()).isZero();
    }

    @Test
    void 워커가_실패한_실행은_전달_확정을_기다리지_않는다() {
        FakePinningMonitor monitor = new FakePinningMonitor(true);

        assertThatThrownBy(() -> serviceWithFailingWorkers(monitor)
                .executeTestWithId("test-pin-fail", fixedCountConfig()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(monitor.awaitCalls.get()).as("저장하지 않는 실행은 기다릴 이유가 없다").isZero();
    }

    @Test
    void 실행이_끝나면_샘플러_전송이_멈춘다() throws Exception {
        List<MetricSend> sends = captureMetricSends("test-sampler-stop");
        service.executeTestWithId("test-sampler-stop", fixedCountConfig());
        int afterRun = sends.size();

        Thread.sleep(SAMPLE_INTERVAL.toMillis() * 4);

        assertThat(sends.size()).as("끝난 실행의 샘플러가 계속 전송하면 안 된다").isEqualTo(afterRun);
    }

    @Test
    void 워커가_실패해도_샘플러_전송은_멈춘다() throws Exception {
        List<MetricSend> sends = captureMetricSends("test-sampler-stop-fail");
        assertThatThrownBy(() -> serviceWithFailingWorkers()
                .executeTestWithId("test-sampler-stop-fail", fixedCountConfig()))
                .isInstanceOf(IllegalStateException.class);
        int afterRun = sends.size();

        Thread.sleep(SAMPLE_INTERVAL.toMillis() * 4);

        assertThat(sends.size()).isEqualTo(afterRun);
    }

    @Test
    void 워커가_예외로_비정상_종료하면_조용히_넘어가지_않고_실패로_드러난다() {
        assertThatThrownBy(() -> serviceWithFailingWorkers().executeTestWithId("test-worker-fail", fixedCountConfig()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test-worker-fail")
                .hasRootCauseMessage("worker down");
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
        assertThatThrownBy(() -> serviceWithFailingWorkers().executeTestWithId("test-budget-fail", platformConfig(3, 2)))
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
