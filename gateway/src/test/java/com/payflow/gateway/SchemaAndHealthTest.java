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
 * Смок-тест стадії 0: застосунок піднімається на справжньому Postgres, Flyway
 * накатує схему, Hibernate її приймає, а сервіс сам звітує, що він здоровий.
 *
 * <p>Навмисно тонкий - жодної бізнес-поведінки тут ще немає. Його завдання -
 * гучно провалити збірку в ту ж мить, коли ламається конфігурація, зв'язування
 * компонентів чи міграції, і саме на це спиратимуться всі наступні стадії.
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
