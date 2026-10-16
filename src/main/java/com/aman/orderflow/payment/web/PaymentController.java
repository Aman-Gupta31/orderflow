package com.aman.orderflow.payment.web;

import com.aman.orderflow.common.idempotency.IdempotencyService;
import com.aman.orderflow.payment.dto.PaymentRequest;
import com.aman.orderflow.payment.dto.PaymentResponse;
import com.aman.orderflow.payment.service.PaymentService;
import com.aman.orderflow.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Idempotency is handled here, at the HTTP boundary, rather than inside
 * PaymentService - a replayed request should never re-run business logic at
 * all, it should just re-serve the exact response the first attempt produced.
 * See docs/learning-guide/07-payments-and-kafka.md for why the replay path
 * writes the raw stored JSON directly instead of re-serializing a fresh object.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public PaymentController(PaymentService paymentService, IdempotencyService idempotencyService,
                              ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<String> pay(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @Valid @RequestBody PaymentRequest request,
                                       @AuthenticationPrincipal UserPrincipal currentUser) throws Exception {
        var existingReplay = idempotencyService.findExistingReplay(idempotencyKey, request);
        if (existingReplay.isPresent()) {
            var replay = existingReplay.get();
            return ResponseEntity.status(replay.status())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(replay.body());
        }

        var payment = paymentService.pay(request, idempotencyKey, currentUser);
        var response = PaymentResponse.from(payment);
        int status = HttpStatus.CREATED.value();

        idempotencyService.record(idempotencyKey, request, status, response);

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(response));
    }

    @GetMapping("/{orderId}")
    public List<PaymentResponse> getByOrder(@PathVariable Long orderId,
                                             @AuthenticationPrincipal UserPrincipal currentUser) {
        return paymentService.getByOrderId(orderId, currentUser).stream()
                .map(PaymentResponse::from)
                .toList();
    }
}
