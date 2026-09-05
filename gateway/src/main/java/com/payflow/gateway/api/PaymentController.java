package com.payflow.gateway.api;

import com.payflow.gateway.domain.Payment;
import com.payflow.gateway.security.MerchantPrincipal;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @AuthenticationPrincipal MerchantPrincipal merchant,
            @Valid @RequestBody CreatePaymentRequest request) {

        Payment payment = paymentService.create(merchant.merchantId(), request);
        PaymentResponse body = PaymentResponse.from(payment);

        return ResponseEntity.created(URI.create("/v1/payments/" + payment.getId())).body(body);
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable UUID id) {
        Payment payment = paymentService.getForMerchant(id, merchant.merchantId());
        return PaymentResponse.from(payment);
    }
}
