package com.msj.securefile.config;

import com.msj.securefile.storage.domain.file.UploadSizePolicy;
import com.msj.securefile.storage.domain.file.UploadTimeoutPolicy;
import com.msj.securefile.storage.domain.scan.ScanQueuePolicy;
import com.msj.securefile.storage.domain.scan.ScanRetryPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class StoragePolicyConfigTest {

    // Values that differ from the defaults, so a test passes only if the property was really bound.
    private static final String[] PROPERTIES = {
            "app.upload.base-delay=90s",
            "app.upload.min-bytes-per-second=2097152",
            "app.upload.max-size-bytes=5000",
            "app.scan.small-max-bytes=1000",
            "app.scan.max-attempts=5",
            "app.scan.base-backoff=20s",
            "app.scan.max-backoff=2m"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StoragePolicyConfig.class);

    @Test
    void uploadTimeoutPolicy_isBoundFromTheProperties() {
        runner.withPropertyValues(PROPERTIES).run(context ->
                assertThat(context.getBean(UploadTimeoutPolicy.class))
                        .isEqualTo(new UploadTimeoutPolicy(Duration.ofSeconds(90), 2_097_152)));
    }

    @Test
    void uploadSizePolicy_isBoundFromTheProperties() {
        runner.withPropertyValues(PROPERTIES).run(context ->
                assertThat(context.getBean(UploadSizePolicy.class)).isEqualTo(new UploadSizePolicy(5_000)));
    }

    @Test
    void scanQueuePolicy_isBoundFromTheProperties() {
        runner.withPropertyValues(PROPERTIES).run(context ->
                assertThat(context.getBean(ScanQueuePolicy.class)).isEqualTo(new ScanQueuePolicy(1_000)));
    }

    @Test
    void scanRetryPolicy_isBoundFromTheProperties() {
        runner.withPropertyValues(PROPERTIES).run(context ->
                assertThat(context.getBean(ScanRetryPolicy.class))
                        .isEqualTo(new ScanRetryPolicy(5, Duration.ofSeconds(20), Duration.ofMinutes(2))));
    }

    @Test
    void startup_failsFastWhenAPolicyValueIsInvalid() {
        // The policies validate themselves: a bad value must stop the application, not run with a broken policy.
        runner.withPropertyValues(PROPERTIES).withPropertyValues("app.upload.min-bytes-per-second=0")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void startup_failsFastWhenTheMaximumSizeIsNotPositive() {
        runner.withPropertyValues(PROPERTIES).withPropertyValues("app.upload.max-size-bytes=0")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void applicationProperties_provideAValidDefaultForEveryPolicy() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(UploadTimeoutPolicy.class);
            assertThat(context).hasSingleBean(UploadSizePolicy.class);
            assertThat(context).hasSingleBean(ScanQueuePolicy.class);
            assertThat(context).hasSingleBean(ScanRetryPolicy.class);
        });
    }
}