package com.loadtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestResultDto {

    private Long executionId;

    private String threadType;

    private int totalRequests;

    private int successCount;

    private int failCount;

    private long avgResponseTimeMs;

    private long minResponseTimeMs;

    private long maxResponseTimeMs;

    private long totalDurationMs;

    private double tps; // Transactions Per Second

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;
}

