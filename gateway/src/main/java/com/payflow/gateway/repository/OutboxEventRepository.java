package com.payflow.gateway.repository;

import com.payflow.gateway.entity.OutboxEvent;
import com.payflow.gateway.entity.status.OutboxEventStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Джерело для gauge-ів {@code payflow.outbox.pending} і
     * {@code payflow.outbox.dead_letter} (стадія 7).
     */
    long countByStatus(OutboxEventStatus status);

    /**
     * "distinct on (payment_id)" у підзапиті гарантує впорядковану per-платіж
     * доставку "безкоштовно": навіть якщо в одного платежу кілька PENDING
     * подій, беремо лише найстарішу - решта лишаються недоторканими (і тому
     * не потрапляють у жоден пакет), поки ця не стане DELIVERED чи
     * DEAD_LETTER.
     *
     * <p>FOR UPDATE SKIP LOCKED стоїть на ЗОВНІШНЬОМУ запиті, а не всередині
     * DISTINCT ON - Postgres забороняє поєднувати FOR UPDATE з DISTINCT ON в
     * одному SELECT. Це дозволяє кільком поллерам одночасно (в межах цього
     * інстансу чи в різних інстансах) розбирати чергу, не блокуючи одне
     * одного і не дублюючи роботу: кожен бачить лише рядки, які ще ніхто не
     * захопив.
     */
    @Query(value = """
            select * from outbox_events
             where id in (
                 select distinct on (payment_id) id
                   from outbox_events
                  where status = 'PENDING'
                    and next_attempt_at <= now()
                  order by payment_id, created_at
             )
             order by created_at
             limit :batchSize
             for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@Param("batchSize") int batchSize);
}
