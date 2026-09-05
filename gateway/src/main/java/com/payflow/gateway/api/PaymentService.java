package com.payflow.gateway.api;

import com.payflow.gateway.domain.Payment;
import com.payflow.gateway.domain.PaymentRepository;
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
