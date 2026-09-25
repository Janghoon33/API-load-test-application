package com.loadtest.controller;

import tools.jackson.databind.ObjectMapper;
import com.loadtest.dto.TestConfigDto;
import com.loadtest.dto.TestResultDto;
import com.loadtest.service.TestExecutionService;
import com.loadtest.support.DirectExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TestController}의 웹 계층 동작을 검증한다.
 * <p>
 * {@code GET /api/tests/results/{id}}에서 서비스가 {@code RuntimeException}을 던지면
 * {@code @RestControllerAdvice}가 없어 예외가 그대로 전파된다. (실제 배포 환경에서는
 * 서블릿 컨테이너의 기본 에러 페이지가 500으로 응답하지만, 의도한 404 대신 처리되지
 * 않은 예외가 그대로 새는 것 자체가 로드맵 C6의 근거다.) 지금 시점의 동작을 고정해 두고,
 * Phase 6에서 {@code @RestControllerAdvice} 도입 후 깔끔한 404로 뒤집는다.
 */
@WebMvcTest(TestController.class)
@Import(TestControllerTest.DirectExecutorConfig.class)
class TestControllerTest {

    /**
     * 컨트롤러는 테스트 실행을 비동기로 시작한다. 여기서는 동기 executor를 주입해서
     * "서비스 호출이 언제 끝나는지"를 기다리거나 추측하지 않고 결정적으로 검증한다.
     * (비동기 executor를 쓰면 늦게 도착한 호출이 다음 테스트의 mock에 새어 검증이 간헐적으로 실패한다.)
     */
    @TestConfiguration
    static class DirectExecutorConfig {
        @Bean("testDispatchExecutor")
        ExecutorService testDispatchExecutor() {
            return new DirectExecutorService();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TestExecutionService testExecutionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 정상_요청은_202와_testId를_반환한다() throws Exception {
        mockMvc.perform(post("/api/tests/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validConfig().build())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.testId").exists())
                .andExpect(jsonPath("$.status").value("STARTED"));

        // 올바른 설정으로 테스트 실행을 시작했는지 확인한다 (동기 executor라 응답 시점에 이미 호출되어 있다)
        verify(testExecutionService).executeTestWithId(anyString(), eq(validConfig().build()));
    }

    // 아래 검증 테스트들은 각각 위반 필드를 "딱 하나"만 만든다.
    // 여러 필드를 동시에 틀리게 하면 한 제약이 사라져도 다른 제약 때문에 400이 나와 테스트가 통과해버린다.

    @Test
    void url이_없으면_400을_반환한다() throws Exception {
        assertBadRequestWithoutExecution(validConfig().url(null).build());
    }

    @Test
    void url이_공백이면_400을_반환한다() throws Exception {
        assertBadRequestWithoutExecution(validConfig().url("   ").build());
    }

    @Test
    void 가상_스레드_수가_0이면_400을_반환한다() throws Exception {
        assertBadRequestWithoutExecution(validConfig().virtualThreads(0).build());
    }

    @Test
    void 스레드당_요청_수가_0이면_400을_반환한다() throws Exception {
        assertBadRequestWithoutExecution(validConfig().requestsPerThread(0).build());
    }

    @Test
    void 스레드_타입이_없으면_400을_반환한다() throws Exception {
        assertBadRequestWithoutExecution(validConfig().threadType(null).build());
    }

    @Test
    void 존재하지_않는_실행결과_조회는_예외가_처리되지_않고_그대로_전파된다() {
        given(testExecutionService.getExecution(99L))
                .willThrow(new RuntimeException("Execution not found: 99"));

        assertThatThrownBy(() -> mockMvc.perform(get("/api/tests/results/99")))
                .as("@RestControllerAdvice가 없어 예외가 그대로 전파된다 (C6)")
                .hasRootCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("Execution not found: 99");
    }

    @Test
    void 존재하는_실행결과는_200과_함께_반환된다() throws Exception {
        TestResultDto result = TestResultDto.builder()
                .executionId(1L)
                .threadType("VIRTUAL")
                .totalRequests(10)
                .successCount(10)
                .failCount(0)
                .tps(5.0)
                .build();
        given(testExecutionService.getExecution(1L)).willReturn(result);

        mockMvc.perform(get("/api/tests/results/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(1))
                .andExpect(jsonPath("$.totalRequests").value(10));
    }

    @Test
    void 헬스체크는_200과_OK를_반환한다() throws Exception {
        mockMvc.perform(get("/api/tests/health"))
                .andExpect(status().isOk());
    }

    /** 모든 필드가 유효한 요청. 검증 테스트는 여기서 필드 하나만 바꿔 쓴다. */
    private TestConfigDto.TestConfigDtoBuilder validConfig() {
        return TestConfigDto.builder()
                .url("http://localhost:8080/ping")
                .threadType(TestConfigDto.ThreadType.VIRTUAL)
                .virtualThreads(1)
                .requestsPerThread(1);
    }

    private void assertBadRequestWithoutExecution(TestConfigDto invalid) throws Exception {
        mockMvc.perform(post("/api/tests/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());

        // 검증에 실패한 요청은 테스트 실행이 시작되면 안 된다
        verifyNoInteractions(testExecutionService);
    }
}
