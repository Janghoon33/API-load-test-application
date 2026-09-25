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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@link TestExecutionService}의 실행 결과 집계(성공 수, TPS, 에러 분류)를 검증한다.
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
