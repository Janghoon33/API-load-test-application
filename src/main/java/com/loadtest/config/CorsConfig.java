package com.loadtest.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;

@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();

        // 모든 origin 허용 (개발 환경)
        config.addAllowedOriginPattern("*");

        // 모든 헤더 허용
        config.addAllowedHeader("*");

        // 모든 HTTP 메서드 허용
        config.addAllowedMethod("*");

        // Credentials 허용 (WebSocket 연결에 필요)
        config.setAllowCredentials(true);

        // Exposed Headers 설정
        config.setExposedHeaders(Arrays.asList(
                "Access-Control-Allow-Origin",
                "Access-Control-Allow-Credentials"
        ));

        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}