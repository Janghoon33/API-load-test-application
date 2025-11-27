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

    @Value("${loadtest.max-platform-threads:500}")
    private int maxPlatformThreads;

    @Value("${loadtest.http-client.connect-timeout:10s}")
    private Duration connectTimeout;

    /**
     * 가상 스레드 Executor
     * Java 21의 핵심 기능 - 경량 스레드로 수만 개 동시 실행 가능
     */
    @Bean(name = "virtualThreadExecutor")
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 일반 플랫폼 스레드 Executor (비교용)
     */
    @Bean(name = "platformThreadExecutor")
    public ExecutorService platformThreadExecutor() {
        return Executors.newFixedThreadPool(maxPlatformThreads);
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