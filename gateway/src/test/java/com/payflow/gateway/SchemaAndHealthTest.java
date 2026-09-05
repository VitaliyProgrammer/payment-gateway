package com.payflow.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.gateway.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Stage 0 smoke test: the application boots against a real Postgres, Flyway applies
 * the schema, Hibernate accepts it, and the service reports itself healthy.
 *
 * <p>Thin by design - there is no behaviour yet. Its job is to make the build fail
 * loudly the moment wiring, migrations or configuration break, which is exactly what
 * every later stage will lean on.
 */
class SchemaAndHealthTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("Flyway applies V1 and creates the merchants table")
    void flywayAppliesInitialMigration() {
        Integer tables = jdbcTemplate.queryForObject("""
                select count(*)
                  from information_schema.tables
                 where table_schema = 'public'
                   and table_name = 'merchants'
                """, Integer.class);

        assertThat(tables).isEqualTo(1);
    }

    @Test
    @DisplayName("the API key hash is unique, so a key can never map to two merchants")
    void apiKeyHashIsUnique() {
        Integer uniqueIndexes = jdbcTemplate.queryForObject("""
                select count(*)
                  from pg_indexes
                 where tablename = 'merchants'
                   and indexdef ilike '%unique%'
                   and indexdef ilike '%api_key_hash%'
                """, Integer.class);

        assertThat(uniqueIndexes).isEqualTo(1);
    }

    @Test
    @DisplayName("/actuator/health reports UP")
    void healthEndpointReportsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
