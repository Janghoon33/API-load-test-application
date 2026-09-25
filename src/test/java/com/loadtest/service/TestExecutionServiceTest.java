package com.loadtest.service;

import com.loadtest.dto.TestConfigDto;
import com.loadtest.dto.TestResultDto;
import com.loadtest.entity.TestExecution;
import com.loadtest.repository.TestExecutionRepository;
import com.loadtest.support.FakeTargetServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

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

    private ExecutorService virtualExecutor;
    private ExecutorService platformExecutor;
    private HttpClient httpClient;
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
        virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        platformExecutor = Executors.newFixedThreadPool(4);
        httpClient = HttpClient.newHttpClient();

        // save() 호출 시 id를 채워 반환한다 (saveExecution()이 saved.getId()를 곧바로 읽으므로 필수)
        given(executionRepository.save(any(TestExecution.class)))
                .willAnswer(invocation -> {
                    TestExecution execution = invocation.getArgument(0);
                    execution.setId(1L);
                    return execution;
                });

        service = new TestExecutionService(
                virtualExecutor, platformExecutor, httpClient, executionRepository, messagingTemplate);
    }

    @AfterEach
    void tearDown() {
        virtualExecutor.shutdownNow();
        platformExecutor.shutdownNow();
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

    private TestConfigDto fixedCountConfig() {
        return fixedCountConfig(targetServer.url());
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
