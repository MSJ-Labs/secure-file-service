package com.msj.securefile.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    // UTC everywhere: the auth tables store zone-less TIMESTAMPs, so one fixed zone keeps them comparable.
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}