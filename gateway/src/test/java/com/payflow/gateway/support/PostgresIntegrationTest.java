package com.payflow.gateway.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Базовий клас для тестів, яким потрібна справжня база даних.
 *
 * <p>Свідомо справжній Postgres, а не H2: коректність цього проєкту спирається на
 * {@code FOR UPDATE SKIP LOCKED}, порушення унікальних констрейнтів під
 * конкурентними вставками та рівні ізоляції транзакцій. In-memory база або не
 * реалізує це, або реалізує інакше, тож "зелений" набір тестів на H2 нічого не
 * доводив би про поведінку, яка тут насправді важлива.
 *
 * <p>{@code @DirtiesContext} тут навмисно, і застосовується до ВСІХ підкласів
 * ({@code @DirtiesContext} успадковується) - без цього Spring кешує
 * ApplicationContext між тестовими класами, а разом з ним лишає ЖИВИМИ фонові
 * SmartLifecycle-біни (PaymentWorkerPool, OutboxPollerPool) із ПОПЕРЕДНЬОГО
 * класу. Ці біни продовжують опитувати ту саму спільну таблицю
 * outbox_events/чергу платежів у фоні, поки виконується НАСТУПНИЙ тестовий
 * клас, - і можуть перехопити його рядки зі своїми (іншими) налаштуваннями
 * (наприклад, іншим max-attempts). Саме так проявився нестабільний (flaky)
 * тест dead-letter на стадії 5: інший, досі "живий" контекст успішно
 * забирав чужу подію собі раніше. Ціна цього рішення - повільніші тести
 * (кожен клас платить за новий контекст), але це набагато дешевше за
 * недетерміновані падіння.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
