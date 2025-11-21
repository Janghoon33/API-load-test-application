package com.loadtest.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "test_executions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String url;

    @Column(name = "thread_type", nullable = false)
    private String threadType;

    @Column(name = "virtual_threads")
    private Integer virtualThreads;

    @Column(name = "requests_per_thread")
    private Integer requestsPerThread;

    @Column(name = "total_requests")
    private Integer totalRequests;

    @Column(name = "success_count")
    private Integer successCount;

    @Column(name = "fail_count")
    private Integer failCount;

    @Column(name = "avg_response_time_ms")
    private Long avgResponseTimeMs;

    @Column(name = "min_response_time_ms")
    private Long minResponseTimeMs;

    @Column(name = "max_response_time_ms")
    private Long maxResponseTimeMs;

    @Column(name = "total_duration_ms")
    private Long totalDurationMs;

    @Column(name = "tps")
    private Double tps;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
