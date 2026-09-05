package com.payflow.gateway.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Базовий клас для тестів, яким потрібна справжня база даних.
 *
 * <p>Свідомо справжній Postgres, а не H2: коректність цього проєкту спирається на
 * {@code FOR UPDATE SKIP LOCKED}, порушення унікальних констрейнтів під
 * конкурентними вставками та рівні ізоляції транзакцій. In-memory база або не
 * реалізує це, або реалізує інакше, тож "зелений" набір тестів на H2 нічого не
 * доводив би про поведінку, яка тут насправді важлива.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class PostgresIntegrationTest {

    /**
     * Запускається один раз на всю JVM і ніколи не зупиняється - тож кожен тестовий
     * клас перевикористовує один і той самий контейнер замість того, щоб знову
     * платити за його запуск. Власний "reaper" Testcontainers прибирає його, коли
     * збірка завершується. Flyway при цьому все одно виконується для кожного
     * Spring-контексту окремо, тож кожен клас бачить повністю змігровану схему.
     */
    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}
