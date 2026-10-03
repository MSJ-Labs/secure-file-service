package com.msj.securefile.shared.infrastructure.persistence;

import java.nio.file.Path;

import org.flywaydb.core.Flyway;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.ForcedType;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.SchemaMappingType;
import org.jooq.meta.jaxb.Target;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Generates the jOOQ code of every bounded-context schema from the Flyway migrations, against a throwaway
 * PostgreSQL container.
 * Used by the {@code jooq-codegen} Maven profile (it runs {@link #main}) and by the drift test.
 * It lives in test sources so that Testcontainers never reaches the production classpath.
 */
public final class JooqCodeGenerator {

    public static final String TARGET_PACKAGE = "com.msj.securefile.shared.infrastructure.persistence.jooq";

    // Same major version as docker-compose.yml, so the generated code matches the runtime database.
    private static final String POSTGRES_IMAGE = "postgres:18.6";
    // Read from the filesystem, not the classpath: the profile runs before process-resources.
    private static final String MIGRATIONS = "filesystem:src/main/resources/db/migration";

    private JooqCodeGenerator() {
    }

    public static void main(String[] args) throws Exception {
        generate(Path.of(args.length > 0 ? args[0] : "src/main/java"));
    }

    public static void generate(Path sourceRoot) throws Exception {
        try (var postgres = new PostgreSQLContainer(POSTGRES_IMAGE)) {
            postgres.start();
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations(MIGRATIONS)
                    .load()
                    .migrate();
            GenerationTool.generate(configuration(postgres, sourceRoot));
        }
    }

    private static Configuration configuration(PostgreSQLContainer postgres, Path sourceRoot) {
        return new Configuration()
                .withJdbc(new Jdbc()
                        .withDriver("org.postgresql.Driver")
                        .withUrl(postgres.getJdbcUrl())
                        .withUser(postgres.getUsername())
                        .withPassword(postgres.getPassword()))
                .withGenerator(new Generator()
                        .withDatabase(new Database()
                                .withName("org.jooq.meta.postgres.PostgresDatabase")
                                // One schema per bounded context, nothing in public. Kept in the output
                                // (no "default schema" flattening) so every query is schema-qualified.
                                .withSchemata(
                                        new SchemaMappingType().withInputSchema("storage"),
                                        new SchemaMappingType().withInputSchema("auth"))
                                // The application works with Instant (injected Clock), not OffsetDateTime.
                                .withForcedTypes(new ForcedType()
                                        .withName("INSTANT")
                                        .withIncludeTypes("(?i:TIMESTAMPTZ|TIMESTAMP\\s+WITH\\s+TIME\\s+ZONE)")))
                        // No timestamped @Generated annotation: it would make every regeneration drift.
                        .withGenerate(new Generate()
                                .withGeneratedAnnotation(false)
                                .withPojos(false)
                                .withDaos(false)
                                .withInterfaces(false)
                                .withDeprecated(false))
                        .withTarget(new Target()
                                .withPackageName(TARGET_PACKAGE)
                                .withDirectory(sourceRoot.toString())));
    }
}