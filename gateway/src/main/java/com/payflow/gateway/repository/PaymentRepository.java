package com.payflow.gateway.repository;

import java.util.Optional;
import java.util.UUID;

import com.payflow.gateway.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /**
     * Обмежено по мерчанту, щоб один мерчант не міг отримати чужий платіж,
     * підібравши або перебравши id - межа ізоляції забезпечується самим запитом,
     * а не окремою перевіркою, яку виклик міг би просто забути зробити.
     */
    Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);
}
