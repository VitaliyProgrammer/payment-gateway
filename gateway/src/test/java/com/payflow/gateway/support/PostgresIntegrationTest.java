package com.payflow.gateway.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for tests that need a real database.
 *
 * <p>Real Postgres rather than H2 on purpose: this project's correctness rests on
 * {@code FOR UPDATE SKIP LOCKED}, unique-constraint violations under concurrent
 * inserts and transaction isolation levels. An in-memory database either does not
 * implement those or implements them differently, so a green H2 suite would prove
 * nothing about the behaviour that matters here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class PostgresIntegrationTest {

    /**
     * Started once for the whole JVM and never stopped, so every test class reuses the
     * same container instead of paying the startup cost again. Testcontainers' own
     * reaper removes it when the build ends. Flyway still runs per Spring context, so
     * each class sees a fully migrated schema.
     */
    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}
