package com.payflow.gateway.conroller;

import com.payflow.gateway.api.CreatePaymentRequest;
import com.payflow.gateway.api.PaymentResponse;
import com.payflow.gateway.entity.Payment;
import com.payflow.gateway.service.PaymentIdempotencyService;
import com.payflow.gateway.security.MerchantPrincipal;
import com.payflow.gateway.service.PaymentService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentIdempotencyService idempotencyService;

    public PaymentController(PaymentService paymentService, PaymentIdempotencyService idempotencyService) {
        this.paymentService = paymentService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @AuthenticationPrincipal MerchantPrincipal merchant,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {

        PaymentResponse body = idempotencyService.createIdempotently(merchant.merchantId(), idempotencyKey, request);

        return ResponseEntity.created(URI.create("/v1/payments/" + body.id())).body(body);
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable UUID id) {
        Payment payment = paymentService.getForMerchant(id, merchant.merchantId());
        return PaymentResponse.from(payment);
    }
}
