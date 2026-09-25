package com.loadtest.config;

import org.springframework.beans.factory.annotation.Qualifier;
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
     * HttpClient 내부 작업(응답 처리 등)을 실행할 가상 스레드 executor.
     * <p>
     * executor를 지정하지 않으면 JDK가 캐시드 <b>플랫폼</b> 스레드 풀을 만들어 쓴다. 그러면 워커는 가상 스레드인데
     * HTTP 클라이언트 내부는 플랫폼 스레드 수에 묶여, 동시 요청이 많을수록 플랫폼 스레드가 수백 개로 늘어난다.
     */
    @Bean(name = "httpClientExecutor")
    public ExecutorService httpClientExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("http-", 0).factory());
    }

    /**
     * HttpClient - HTTP/1.1로 변경 (동시 스트림 제한 회피)
     */
    @Bean
    public HttpClient httpClient(@Qualifier("httpClientExecutor") ExecutorService httpClientExecutor) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                .executor(httpClientExecutor)
                .build();
    }
}
