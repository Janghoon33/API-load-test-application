package com.loadtest.repository;

import com.loadtest.entity.TestExecution;
import com.loadtest.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Postgres(Testcontainers)에 대해 리포지토리 계층을 검증한다.
 * Phase 4에서 추가할 JPQL 집계 쿼리·페이징의 토대가 된다.
 */
@DataJpaTest
class TestExecutionRepositoryTest extends PostgresTestBase {

    @Autowired
    private TestExecutionRepository repository;

    @Test
    void 실행_이력은_시작시각_내림차순으로_조회된다() {
        LocalDateTime now = LocalDateTime.now();
        repository.save(execution("http://a", now.minusMinutes(10)));
        repository.save(execution("http://b", now));
        repository.save(execution("http://c", now.minusMinutes(5)));

        List<TestExecution> results = repository.findAllByOrderByStartedAtDesc();

        assertThat(results).hasSize(3);
        assertThat(results).extracting(TestExecution::getUrl)
                .containsExactly("http://b", "http://c", "http://a");
    }

    private TestExecution execution(String url, LocalDateTime startedAt) {
        return TestExecution.builder()
                .url(url)
                .threadType("VIRTUAL")
                .virtualThreads(1)
                .requestsPerThread(1)
                .totalRequests(1)
                .successCount(1)
                .failCount(0)
                .avgResponseTimeMs(1L)
                .minResponseTimeMs(1L)
                .maxResponseTimeMs(1L)
                .totalDurationMs(1L)
                .tps(1.0)
                .startedAt(startedAt)
                .completedAt(startedAt.plusSeconds(1))
                .build();
    }
}
