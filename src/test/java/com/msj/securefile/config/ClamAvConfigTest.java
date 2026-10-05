package com.msj.securefile.config;

import com.msj.securefile.storage.infrastructure.adapters.scanner.ClamAvSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ClamAvConfigTest {

    // Values that differ from the defaults, so a test passes only if the property was really bound.
    private static final String[] PROPERTIES = {
            "app.scanner.clamav.host=clamd.example.test",
            "app.scanner.clamav.port=3999",
            "app.scanner.clamav.connect-timeout=2s",
            "app.scanner.clamav.read-timeout=15m"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ClamAvConfig.class);

    @Test
    void settings_areBoundFromTheProperties() {
        runner.withPropertyValues(PROPERTIES).run(context ->
                assertThat(context.getBean(ClamAvSettings.class)).isEqualTo(
                        new ClamAvSettings("clamd.example.test", 3_999, Duration.ofSeconds(2), Duration.ofMinutes(15))));
    }

    @Test
    void startup_failsFastWhenASettingIsInvalid() {
        // The settings validate themselves: a bad value must stop the application, not run with a broken scanner.
        runner.withPropertyValues(PROPERTIES).withPropertyValues("app.scanner.clamav.port=0")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void applicationProperties_provideAValidDefaultForEverySetting() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ClamAvSettings.class);
        });
    }
}
