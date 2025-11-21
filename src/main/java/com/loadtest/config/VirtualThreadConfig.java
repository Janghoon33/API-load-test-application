package com.loadtest.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

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
     */
    @Bean(name = "virtualThreadExecutor")
    @Primary
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
     * HttpClient - HTTP/2 지원, 연결 재사용
     */
    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_2)
                .build();
    }
}