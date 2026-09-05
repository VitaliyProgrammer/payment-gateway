package com.payflow.gateway.service;

import com.payflow.gateway.api.CreatePaymentRequest;
import com.payflow.gateway.exception.PaymentNotFoundException;
import com.payflow.gateway.entity.Payment;
import com.payflow.gateway.repository.PaymentRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public Payment create(UUID merchantId, CreatePaymentRequest request) {
        Payment payment = new Payment(UUID.randomUUID(), merchantId, request.amount(), request.currency());
        return paymentRepository.save(payment);
    }

    @Transactional(readOnly = true)
    public Payment getForMerchant(UUID paymentId, UUID merchantId) {
        return paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
}
