package com.baropick.delivery.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {
    @Bean
    Clock clock() {
        // DB(timestamp(6)) 정밀도와 맞추기 위해 마이크로초 단위로 절삭 — 저장 후 비교 시 반올림으로 인한 역전 방지
        return Clock.tick(Clock.systemUTC(), java.time.Duration.ofNanos(1000));
    }
}
