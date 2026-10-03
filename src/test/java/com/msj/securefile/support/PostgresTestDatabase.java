package com.msj.securefile.support;

import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container shared by every integration test of the JVM, with the real Flyway migrations applied.
 * Starting a container per test class would dominate the build time. Testcontainers' Ryuk removes it on exit.
 */
public final class PostgresTestDatabase {

    // Same major version as docker-compose.yml, so tests run against the runtime database.
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");
    private static final DSLContext DSL_CONTEXT;

    static {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        DSL_CONTEXT = DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    private PostgresTestDatabase() {
    }

    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    public static String username() {
        return POSTGRES.getUsername();
    }

    public static String password() {
        return POSTGRES.getPassword();
    }

    public static DSLContext dsl() {
        return DSL_CONTEXT;
    }

    /** Removes every account (and, by cascade, its roles and tokens). The seeded roles are kept. */
    public static void deleteAllAccounts() {
        DSL_CONTEXT.execute("DELETE FROM auth.user_account");
    }
}