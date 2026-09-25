package com.loadtest.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Testcontainers Postgres를 띄우는 통합 테스트 베이스.
 * <p>
 * {@code docker-compose.yml}과 동일한 {@code postgres:15} 이미지를 사용한다.
 * 컨테이너는 클래스당 하나만 기동되어(static) 테스트 클래스 간 재사용되며,
 * {@link ServiceConnection}이 Spring 데이터소스 설정을 자동으로 이 컨테이너로 연결한다.
 * <p>
 * 서브클래스는 {@code @SpringBootTest} 또는 {@code @DataJpaTest} 등 Spring 테스트
 * 애노테이션을 직접 붙인다 (SpringExtension은 그쪽에서 이미 등록된다).
 * <p>
 * <b>Docker 필요</b>: {@code @Testcontainers}의 컨테이너 기동은 JUnit 확장 콜백 중
 * 가장 먼저 실행되므로, {@code @BeforeAll} 가드로 "Docker 없으면 스킵"을 걸 수 없다
 * (실측 확인함 — 가드보다 먼저 컨테이너 기동을 시도해 그대로 실패한다).
 * 로컬에서 이 베이스를 쓰는 테스트를 돌리려면 Docker Desktop이 떠 있어야 하고,
 * 없으면 {@code DockerClientProviderStrategy}의 명확한 오류로 실패한다 (CI는 Docker 사용 가능).
 */
@Testcontainers
public abstract class PostgresTestBase {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15");
}
