package com.loadtest.benchmark;

import com.loadtest.dto.TestConfigDto;
import com.loadtest.dto.TestResultDto;
import com.loadtest.monitor.PinningMonitor;
import com.loadtest.service.TestExecutionService;
import com.loadtest.support.FakeTargetServer;
import com.loadtest.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 가상 스레드 사용 방식(핀닝, HttpClient 스레드 풀 등)의 개선 전/후를 수치로 비교하는 벤치마크.
 * <p>
 * 평소 {@code ./gradlew test}에서는 실행되지 않는다(환경변수 {@code BENCHMARK=true}일 때만).
 * <pre>
 * BENCHMARK=true BENCH_LABEL=baseline ./gradlew test --tests '*PinningBenchmarkTest'
 * </pre>
 * 결과는 표로 표준 출력에 찍히고 {@code build/benchmark/<label>.md}에도 저장된다.
 * <p>
 * Spring 컨텍스트 전체를 띄워 빈을 주입받으므로 서비스 생성자 시그니처가 바뀌어도
 * 개선 전/후 코드에서 동일하게 동작한다 — 그래야 같은 조건으로 비교할 수 있다.
 * <p>
 * 주의: 대상 서버·앱·DB가 한 머신에서 돌아가므로 절대 수치보다 <b>같은 머신에서의 상대 비교</b>에 의미가 있다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "BENCHMARK", matches = "true")
class PinningBenchmarkTest extends PostgresTestBase {

    private static final int WARMUP_RUNS = 1;
    private static final int MEASURED_RUNS = 3;
    /** JFR 스트림은 배치로 전달되므로 스냅샷 전에 충분히 기다린다 */
    private static final Duration JFR_SETTLE = Duration.ofMillis(2500);

    @Autowired
    private TestExecutionService service;

    @Autowired
    private PinningMonitor pinningMonitor;

    private record Scenario(String name, String description, int status, Duration delay,
                            boolean requestLogging, int virtualUsers, int requestsPerUser) {
        int totalRequests() {
            return virtualUsers * requestsPerUser;
        }
    }

    private record Result(Scenario scenario, double medianTps, long medianDurationMs,
                          int success, int fail, int peakPlatformThreads, long httpClientWorkers,
                          Map<String, Integer> errors, PinningMonitor.Snapshot pinning) {
    }

    @Test
    void 벤치마크를_실행한다() throws Exception {
        // 모니터가 꺼져 있으면 "핀닝 0건"이 아무 의미가 없으므로 즉시 실패시킨다
        org.assertj.core.api.Assertions.assertThat(pinningMonitor.isRunning())
                .as("PinningMonitor가 실행 중이어야 한다").isTrue();

        List<Scenario> scenarios = List.of(
                new Scenario("S1", "에러 폭주(500) + 요청 로깅 켬", 500, Duration.ZERO, true, 200, 50),
                new Scenario("S2", "정상 응답(200) + 요청 로깅 끔", 200, Duration.ZERO, false, 1000, 10),
                new Scenario("S3", "고동시성(200, 20ms 지연)", 200, Duration.ofMillis(20), false, 1500, 6),
                // S1과 동일 조건에서 요청 로깅만 끈 것 (개선 전 코드는 이 옵션을 무시하므로 S1과 같게 나온다)
                new Scenario("S4", "에러 폭주(500) + 요청 로깅 끔", 500, Duration.ZERO, false, 200, 50),
                // S1을 마지막에 다시 측정한다. S1과 값이 같으면 실행 순서가 결과를 왜곡하지 않은 것이다.
                new Scenario("S5", "S1 재측정 (순서 효과 확인)", 500, Duration.ZERO, true, 200, 50));

        // 전체 시나리오를 한 번 통째로 돌려 JIT·클래스 로딩·커넥션 풀을 데운 뒤, 두 번째 패스만 기록한다.
        // (시나리오별 워밍업만으로는 첫 시나리오가 콜드 상태로 측정되어 순서에 따라 값이 왜곡된다)
        for (Scenario scenario : scenarios) {
            run(scenario);
        }

        List<Result> results = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            results.add(run(scenario));
        }

        String report = render(results);
        System.out.println(report);

        String label = System.getenv().getOrDefault("BENCH_LABEL", "run");
        Path out = Path.of("build", "benchmark", label + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
    }

    private Result run(Scenario scenario) throws Exception {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();

        try (FakeTargetServer server = FakeTargetServer.start().respondWith(scenario.status(), scenario.delay())) {
            TestConfigDto config = TestConfigDto.builder()
                    .url(server.url())
                    .threadType(TestConfigDto.ThreadType.VIRTUAL)
                    .virtualThreads(scenario.virtualUsers())
                    .requestsPerThread(scenario.requestsPerUser())
                    .enableLogging(scenario.requestLogging())
                    .build();

            for (int i = 0; i < WARMUP_RUNS; i++) {
                service.executeTestWithId("bench-" + scenario.name() + "-warmup-" + i, config);
            }
            Thread.sleep(JFR_SETTLE);
            PinningMonitor.Snapshot before = pinningMonitor.snapshot();

            List<Double> tps = new ArrayList<>();
            List<Long> durations = new ArrayList<>();
            int success = 0;
            int fail = 0;
            Map<String, Integer> errors = new java.util.TreeMap<>();

            // 워밍업 때 만들어진 스레드도 포함해서 이 시나리오 전체의 피크를 본다
            threads.resetPeakThreadCount();

            for (int i = 0; i < MEASURED_RUNS; i++) {
                System.gc();
                Thread.sleep(300);

                TestResultDto result = service.executeTestWithId("bench-" + scenario.name() + "-" + i, config);

                tps.add(result.getTps());
                durations.add(result.getTotalDurationMs());
                success += result.getSuccessCount();
                fail += result.getFailCount();
                result.getErrorBreakdown().forEach((k, v) -> errors.merge(k, v, Integer::sum));
            }

            int peakThreads = threads.getPeakThreadCount();
            long httpClientWorkers = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.getName().startsWith("HttpClient-") && t.getName().contains("-Worker-"))
                    .count();

            Thread.sleep(JFR_SETTLE);
            PinningMonitor.Snapshot pinning = pinningMonitor.snapshot().minus(before);

            return new Result(scenario, median(tps), (long) median(durations.stream().map(Long::doubleValue).toList()),
                    success, fail, peakThreads, httpClientWorkers, errors, pinning);
        }
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private String render(List<Result> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## 벤치마크 결과 (label=")
                .append(System.getenv().getOrDefault("BENCH_LABEL", "run")).append(")\n\n");
        sb.append("- JDK: ").append(System.getProperty("java.vm.name")).append(' ')
                .append(Runtime.version()).append('\n');
        sb.append("- OS/CPU: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.arch")).append(" / ")
                .append(Runtime.getRuntime().availableProcessors()).append(" cores\n");
        sb.append("- 힙 상한: ").append(Runtime.getRuntime().maxMemory() / 1024 / 1024).append(" MB")
                .append(" / 측정 ").append(MEASURED_RUNS).append("회 중앙값(워밍업 ").append(WARMUP_RUNS).append("회 제외)\n\n");

        sb.append("| 시나리오 | 조건 | 요청 수/회 | TPS(중앙값) | 소요 ms(중앙값) | 성공/실패(합) | 피크 플랫폼 스레드 | HttpClient 워커 스레드 | 핀닝 이벤트(합) | 핀닝 총 ms |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (Result r : results) {
            sb.append(String.format("| %s | %s | %d | %.0f | %d | %d/%d | %d | %d | %d | %d |%n",
                    r.scenario().name(), r.scenario().description(), r.scenario().totalRequests(),
                    r.medianTps(), r.medianDurationMs(), r.success(), r.fail(),
                    r.peakPlatformThreads(), r.httpClientWorkers(), r.pinning().count(), r.pinning().totalMillis()));
        }

        sb.append("\n> 피크 플랫폼 스레드는 JVM 전체(Spring/Tomcat/DB 풀 포함) 기준 절대값이며, 앞 시나리오에서 만들어진 캐시 스레드(HttpClient 기본 풀은 60초 유지)가 남아 있을 수 있다.\n");
        sb.append("\n### 에러 유형 (측정 3회 합)\n");
        for (Result r : results) {
            sb.append("- ").append(r.scenario().name()).append(": ").append(r.errors().isEmpty() ? "(없음)" : r.errors()).append('\n');
        }
        sb.append("\n### 핀닝 발생 위치 (상위 스택)\n");
        for (Result r : results) {
            sb.append("\n**").append(r.scenario().name()).append("** — ").append(r.pinning().count()).append("건\n");
            if (r.pinning().stacks().isEmpty()) {
                sb.append("- (없음)\n");
            } else {
                r.pinning().stacks().entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                        .limit(5)
                        .forEach(e -> sb.append("- ").append(e.getValue()).append("회: `").append(e.getKey()).append("`\n"));
            }
        }
        return sb.toString();
    }
}
