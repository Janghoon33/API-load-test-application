package com.loadtest.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class VirtualThreadConfig {

    private final Duration connectTimeout;

    public VirtualThreadConfig(@Value("${loadtest.http-client.connect-timeout:10s}") Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /**
     * 컨트롤러가 "테스트 실행"을 백그라운드로 시작할 때 쓰는 가상 스레드 executor.
     * 요청 하나를 처리하는 동안 블로킹되어도 되므로 가상 스레드가 적합하다
     * (공용 ForkJoinPool.commonPool을 점유하지 않는다).
     * <p>
     * 워커 스레드(부하를 만드는 스레드)는 여기가 아니라 실행마다
     * {@link com.loadtest.service.RunExecutorFactory}가 새로 만든다.
     */
    @Bean(name = "testDispatchExecutor")
    public ExecutorService testDispatchExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("dispatch-", 0).factory());
    }

    /**
     * HttpClient - HTTP/1.1로 변경 (동시 스트림 제한 회피)
     */
    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }
}
