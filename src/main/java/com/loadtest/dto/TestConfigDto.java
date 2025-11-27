package com.loadtest.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestConfigDto {

    @NotBlank(message = "URL은 필수입니다")
    private String url;

    @NotNull(message = "스레드 타입은 필수입니다")
    private ThreadType threadType;

    @Min(value = 1, message = "가상 스레드 수는 최소 1 이상이어야 합니다")
    private int virtualThreads;

    @Min(value = 1, message = "스레드당 요청 수는 최소 1 이상이어야 합니다")
    private int requestsPerThread;

    private String method; // GET, POST, PUT, DELETE

    private Map<String, String> headers;

    private String body;

    private Boolean enableLogging;

    public enum ThreadType {
        VIRTUAL, PLATFORM
    }

    // method null 체크 헬퍼 메서드
    public String getMethodOrDefault() {
        return method != null && !method.isBlank() ? method : "GET";
    }

    // enableLogging null 체크용 헬퍼 메서드
    public boolean isEnableLogging() {
        return enableLogging != null && enableLogging;
    }
}