package com.server.zelo.domain.payment.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.common.auth.Auth;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.domain.payment.dto.response.CreatePaymentResponse;
import com.server.zelo.domain.payment.facade.PaymentFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentFacade paymentFacade;

    @PostMapping("/{orderId}")
    public ResponseEntity<ApiResponse<CreatePaymentResponse>> createPayment(@Auth AuthUser authUser,
                                                                            @PathVariable Long orderId) {
        return ResponseEntity.ok(ApiResponse.ok(paymentFacade.createPayment(authUser.id(), orderId)));
    }
}
