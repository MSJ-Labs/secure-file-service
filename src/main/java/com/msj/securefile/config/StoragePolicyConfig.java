package com.msj.securefile.config;

import com.msj.securefile.storage.domain.file.UploadTimeoutPolicy;
import com.msj.securefile.storage.domain.scan.ScanQueuePolicy;
import com.msj.securefile.storage.domain.scan.ScanRetryPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires the policies of the storage domain from the configuration. The domain stays free of Spring: the properties
 * live in these records and each policy validates its own values, so a bad value stops the application at startup.
 * The numbers are working hypotheses to calibrate with a load test.
 */
@Configuration
@EnableConfigurationProperties({StoragePolicyConfig.UploadProperties.class, StoragePolicyConfig.ScanProperties.class})
public class StoragePolicyConfig {

    @ConfigurationProperties(prefix = "app.upload")
    public record UploadProperties(Duration baseDelay, long minBytesPerSecond) {
    }

    @ConfigurationProperties(prefix = "app.scan")
    public record ScanProperties(long smallMaxBytes, int maxAttempts, Duration baseBackoff, Duration maxBackoff) {
    }

    @Bean
    UploadTimeoutPolicy uploadTimeoutPolicy(UploadProperties properties) {
        return new UploadTimeoutPolicy(properties.baseDelay(), properties.minBytesPerSecond());
    }

    @Bean
    ScanQueuePolicy scanQueuePolicy(ScanProperties properties) {
        return new ScanQueuePolicy(properties.smallMaxBytes());
    }

    @Bean
    ScanRetryPolicy scanRetryPolicy(ScanProperties properties) {
        return new ScanRetryPolicy(properties.maxAttempts(), properties.baseBackoff(), properties.maxBackoff());
    }
}