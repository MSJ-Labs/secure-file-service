package com.msj.securefile.config;

import com.msj.securefile.storage.infrastructure.adapters.storage.S3StorageSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class S3ConfigTest {

    // Values that differ from the defaults, so a test passes only if the property was really bound.
    private static final String[] PROPERTIES = {
            "app.storage.s3.endpoint=http://s3.example.test:9000",
            "app.storage.s3.region=eu-west-3",
            "app.storage.s3.access-key=an-access-key",
            "app.storage.s3.secret-key=a-secret-key",
            "app.storage.s3.path-style-access=true",
            "app.storage.s3.quarantine-bucket=incoming",
            "app.storage.s3.clean-bucket=published",
            "app.storage.s3.part-size-bytes=8388608"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(S3Config.class);

    @Test
    void settings_areBoundFromTheProperties() {
        runner.withPropertyValues(PROPERTIES).run(context ->
                assertThat(context.getBean(S3StorageSettings.class))
                        .isEqualTo(new S3StorageSettings("incoming", "published", 8_388_608)));
    }

    @Test
    void client_talksToTheConfiguredEndpointInTheConfiguredRegion() {
        runner.withPropertyValues(PROPERTIES).run(context -> {
            S3Client client = context.getBean(S3Client.class);

            assertThat(client.serviceClientConfiguration().endpointOverride())
                    .contains(URI.create("http://s3.example.test:9000"));
            assertThat(client.serviceClientConfiguration().region()).isEqualTo(Region.of("eu-west-3"));
        });
    }

    @Test
    void client_usesPathStyleAddressingWhenAsked() {
        runner.withPropertyValues(PROPERTIES).run(context -> {
            S3Client client = context.getBean(S3Client.class);

            // LocalStack and most S3-compatible stores have no wildcard DNS: the bucket goes in the path.
            assertThat(client.utilities().getUrl(request -> request.bucket("incoming").key("42")))
                    .hasToString("http://s3.example.test:9000/incoming/42");
        });
    }

    @Test
    void client_hasNoEndpointOverrideWhenTheEndpointIsBlank() {
        // Real S3: the SDK resolves the endpoint from the region.
        runner.withPropertyValues(PROPERTIES).withPropertyValues("app.storage.s3.endpoint=", "app.storage.s3.path-style-access=false")
                .run(context -> assertThat(context.getBean(S3Client.class).serviceClientConfiguration().endpointOverride())
                        .isEmpty());
    }

    @Test
    void startup_failsFastWhenTheSettingsAreInvalid() {
        // The settings validate themselves: a bad value must stop the application, not run with a broken storage.
        runner.withPropertyValues(PROPERTIES).withPropertyValues("app.storage.s3.part-size-bytes=0")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void applicationProperties_provideAValidDefaultForEverythingButTheCredentials() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("app.storage.s3.access-key=test", "app.storage.s3.secret-key=test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(S3Client.class);
                    assertThat(context).hasSingleBean(S3StorageSettings.class);
                });
    }
}
