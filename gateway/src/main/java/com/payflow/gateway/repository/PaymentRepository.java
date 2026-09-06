package com.payflow.gateway.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.payflow.gateway.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /**
     * Обмежено по мерчанту, щоб один мерчант не міг отримати чужий платіж,
     * підібравши або перебравши id - межа ізоляції забезпечується самим запитом,
     * а не окремою перевіркою, яку виклик міг би просто забути зробити.
     */
    Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);

    /**
     * Пакет платежів для примирення (стадія 6). Дві категорії, один запит:
     *
     * <ul>
     *   <li>{@code NEEDS_RECONCILIATION} з {@code reconcile_next_at <= now()} -
     *       воркер уже вирішив, що підсумок невідомий, і час чергової спроби
     *       дізнатись його настав;</li>
     *   <li>{@code PROCESSING}, не оновлений довше за {@code staleBefore} -
     *       воркер (чи цілий інстанс) помер посеред виклику до еквайра, і цей
     *       платіж уже нікого не має, хто довів би його до кінця.</li>
     * </ul>
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} - той самий патерн, що й у черзі outbox
     * (V6): кілька sweeper-ів (у цьому інстансі чи в різних) розбирають чергу
     * паралельно, не блокуючи одне одного й не дублюючи роботу. {@code FOR
     * UPDATE} на час короткої транзакції захоплення також не дає паралельному
     * capture/cancel мерчанта втрутитись між читанням і записом.
     */
    @Query(value = """
            select * from payments
             where (status = 'NEEDS_RECONCILIATION' and reconcile_next_at <= now())
                or (status = 'PROCESSING' and updated_at < :staleBefore)
             order by updated_at
             limit :batchSize
             for update skip locked
            """, nativeQuery = true)
    List<Payment> lockReconcilableBatch(@Param("staleBefore") Instant staleBefore, @Param("batchSize") int batchSize);
}
