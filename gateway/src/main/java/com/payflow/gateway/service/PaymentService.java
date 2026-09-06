package com.payflow.gateway.service;

import com.payflow.gateway.api.CreatePaymentRequest;
import com.payflow.gateway.exception.PaymentNotFoundException;
import com.payflow.gateway.entity.Payment;
import com.payflow.gateway.metrics.PaymentMetrics;
import com.payflow.gateway.repository.PaymentRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentMetrics metrics;

    public PaymentService(PaymentRepository paymentRepository, PaymentMetrics metrics) {
        this.paymentRepository = paymentRepository;
        this.metrics = metrics;
    }

    /**
     * Приймає готовий id, а не генерує його сам: викликач (див.
     * PaymentIdempotencyService) резервує слот у черзі обробки ще ДО цього
     * виклику, використовуючи цей самий id - а резервувати слот під ще не
     * згенерований id неможливо.
     */
    @Transactional
    public Payment create(UUID paymentId, UUID merchantId, CreatePaymentRequest request) {
        Payment payment = new Payment(paymentId, merchantId, request.amount(), request.currency());
        Payment saved = paymentRepository.save(payment);
        metrics.paymentAccepted(request.currency());
        return saved;
    }

    @Transactional(readOnly = true)
    public Payment getForMerchant(UUID paymentId, UUID merchantId) {
        return paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
}
