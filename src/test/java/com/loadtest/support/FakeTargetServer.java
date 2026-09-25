package com.loadtest.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 부하 테스트 대상 역할을 하는 가벼운 HTTP 서버.
 * <p>
 * 의존성 없이 JDK 내장 {@link HttpServer}만으로 동작하며, 가상 스레드 executor를 사용해
 * 수천 개 동시 요청도 문제없이 받는다. 상태코드와 응답 지연을 자유롭게 바꿀 수 있어
 * 성공/실패, 타임아웃, 지연 분포 등 다양한 시나리오를 테스트에서 재현할 수 있다.
 */
public class FakeTargetServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger statusCode = new AtomicInteger(200);
    private final AtomicReference<Duration> delay = new AtomicReference<>(Duration.ZERO);
    private final AtomicLong receivedCount = new AtomicLong(0);

    private FakeTargetServer(HttpServer server) {
        this.server = server;
    }

    public static FakeTargetServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            FakeTargetServer fake = new FakeTargetServer(server);

            server.createContext("/", exchange -> {
                fake.receivedCount.incrementAndGet();
                Duration currentDelay = fake.delay.get();
                if (!currentDelay.isZero()) {
                    try {
                        Thread.sleep(currentDelay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                byte[] body = "OK".getBytes();
                exchange.sendResponseHeaders(fake.statusCode.get(), body.length);
                try (var os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });

            server.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException("FakeTargetServer 기동 실패", e);
        }
    }

    /** 이후 모든 응답의 상태코드를 지정한다. */
    public FakeTargetServer respondWith(int statusCode) {
        this.statusCode.set(statusCode);
        return this;
    }

    /** 상태코드와 응답 지연을 함께 지정한다 (백분위·타임아웃 시나리오 검증용). */
    public FakeTargetServer respondWith(int statusCode, Duration responseDelay) {
        this.statusCode.set(statusCode);
        this.delay.set(responseDelay);
        return this;
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/";
    }

    /** 지금까지 수신한 요청 수 (부하 프로파일 정확도 검증용). */
    public long receivedCount() {
        return receivedCount.get();
    }

    public void resetReceivedCount() {
        receivedCount.set(0);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
