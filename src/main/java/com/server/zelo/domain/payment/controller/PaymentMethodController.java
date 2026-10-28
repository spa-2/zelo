package com.server.zelo.domain.payment.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.common.auth.Auth;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.domain.payment.dto.request.CreatePaymentMethodRequest;
import com.server.zelo.domain.payment.dto.response.CreatePaymentMethodResponse;
import com.server.zelo.domain.payment.dto.response.GetPaymentMethodResponse;
import com.server.zelo.domain.payment.entity.PaymentMethod;
import com.server.zelo.domain.payment.service.PaymentMethodCommandService;
import com.server.zelo.domain.payment.service.PaymentMethodService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payment-methods")
public class PaymentMethodController {

    private final PaymentMethodService paymentMethodService;
    private final PaymentMethodCommandService paymentMethodCommandService;

    @PostMapping
    public ResponseEntity<ApiResponse<CreatePaymentMethodResponse>> createPaymentMethod(
            @Auth AuthUser authUser,
            @Valid @RequestBody CreatePaymentMethodRequest request) {
        PaymentMethod paymentMethod = paymentMethodCommandService.createPaymentMethod(authUser.id(), request.billingKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(CreatePaymentMethodResponse.from(paymentMethod)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<GetPaymentMethodResponse>>> getPaymentMethods(@Auth AuthUser authUser) {
        List<GetPaymentMethodResponse> responses = paymentMethodService.findPaymentMethods(authUser.id()).stream()
                .map(GetPaymentMethodResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(responses));
    }

    @DeleteMapping("/{paymentMethodId}")
    public ResponseEntity<Void> deletePaymentMethod(@Auth AuthUser authUser, @PathVariable Long paymentMethodId) {
        paymentMethodCommandService.deletePaymentMethod(authUser.id(), paymentMethodId);
        return ResponseEntity.noContent().build();
    }
}