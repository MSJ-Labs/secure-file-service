package com.msj.securefile.shared.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fails when the committed jOOQ code no longer matches the Flyway migrations.
 * Fix: {@code mvn -Pjooq-codegen generate-sources}, then commit the result.
 */
class JooqCodeDriftIT {

    private static final Path COMMITTED_SOURCES = Path.of("src/main/java");
    private static final Path PACKAGE_DIR = Path.of(JooqCodeGenerator.TARGET_PACKAGE.replace('.', '/'));

    @Test
    void committedJooqCodeMatchesMigrations(@TempDir Path regenerated) throws Exception {
        JooqCodeGenerator.generate(regenerated);

        assertThat(contentsOf(regenerated.resolve(PACKAGE_DIR)))
                .as("jOOQ code regenerated from the migrations vs committed code")
                .isNotEmpty()
                .isEqualTo(contentsOf(COMMITTED_SOURCES.resolve(PACKAGE_DIR)));
    }

    private static Map<String, String> contentsOf(Path root) throws IOException {
        Map<String, String> contents = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile).forEach(file -> contents.put(
                    root.relativize(file).toString(), read(file).replace("\r\n", "\n")));
        }
        return contents;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}