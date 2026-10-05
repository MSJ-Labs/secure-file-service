package com.msj.securefile.config;

import com.msj.securefile.storage.infrastructure.adapters.scanner.ClamAvSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * The settings of the ClamAV client, from app.scanner.clamav.*. The settings validate themselves, so a bad value stops
 * the application at startup instead of showing up as scans that never complete.
 */
@Configuration
@EnableConfigurationProperties(ClamAvConfig.ClamAvProperties.class)
public class ClamAvConfig {

    @ConfigurationProperties(prefix = "app.scanner.clamav")
    public record ClamAvProperties(String host, int port, Duration connectTimeout, Duration readTimeout) {
    }

    @Bean
    ClamAvSettings clamAvSettings(ClamAvProperties properties) {
        return new ClamAvSettings(properties.host(), properties.port(), properties.connectTimeout(),
                properties.readTimeout());
    }
}
