package com.msj.securefile.config;

import com.msj.securefile.storage.infrastructure.adapters.storage.S3StorageSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

/**
 * The S3 client and the settings of the storage adapter, from app.storage.s3.*. The credentials have no default on
 * purpose: nothing secret lives in application.properties. An empty endpoint means the real S3, which the SDK resolves
 * from the region; LocalStack and the other compatible stores need an endpoint and path-style addressing.
 */
@Configuration
@EnableConfigurationProperties(S3Config.S3Properties.class)
public class S3Config {

    @ConfigurationProperties(prefix = "app.storage.s3")
    public record S3Properties(String endpoint, String region, String accessKey, String secretKey,
                               boolean pathStyleAccess, String quarantineBucket, String cleanBucket,
                               int partSizeBytes) {
    }

    @Bean
    S3StorageSettings s3StorageSettings(S3Properties properties) {
        return new S3StorageSettings(properties.quarantineBucket(), properties.cleanBucket(), properties.partSizeBytes());
    }

    @Bean
    S3Client s3Client(S3Properties properties) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .forcePathStyle(properties.pathStyleAccess());
        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return builder.build();
    }
}
