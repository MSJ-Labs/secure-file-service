package com.msj.securefile.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    // UTC everywhere: one fixed zone keeps the stored instants comparable.
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}