package com.loadtest.config;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class VirtualThreadConfigTest {

    private final VirtualThreadConfig config = new VirtualThreadConfig(Duration.ofSeconds(1));

    @Test
    void HttpClient는_가상_스레드_executor를_사용한다() throws Exception {
        try (ExecutorService executor = config.httpClientExecutor()) {
            HttpClient client = config.httpClient(executor);

            assertThat(client.executor()).as("executor를 지정하지 않으면 JDK 기본 플랫폼 스레드 풀을 쓴다").isPresent();
            assertThat(client.executor().get()).isSameAs(executor);

            // 클라이언트의 executor가 실제로 어떤 스레드에서 작업을 실행하는지 확인한다
            CompletableFuture<Thread> ranOn = new CompletableFuture<>();
            client.executor().get().execute(() -> ranOn.complete(Thread.currentThread()));
            Thread thread = ranOn.get(5, TimeUnit.SECONDS);

            assertThat(thread.isVirtual()).isTrue();
            assertThat(thread.getName()).startsWith("http-");
        }
    }

    @Test
    void 테스트_실행_시작용_executor는_가상_스레드에서_실행한다() throws Exception {
        try (ExecutorService executor = config.testDispatchExecutor()) {
            Future<Boolean> isVirtual = executor.submit(() -> Thread.currentThread().isVirtual());
            Future<String> name = executor.submit(() -> Thread.currentThread().getName());

            assertThat(isVirtual.get()).isTrue();
            assertThat(name.get()).startsWith("dispatch-");
        }
    }

    @Test
    void HttpClient는_HTTP_1_1을_사용한다() {
        try (ExecutorService executor = config.httpClientExecutor()) {
            assertThat(config.httpClient(executor).version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        }
    }
}
