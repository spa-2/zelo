package com.server.zelo.domain.payment.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.common.auth.Auth;
import com.server.zelo.common.auth.AuthUser;

import com.server.zelo.domain.payment.dto.response.PaymentConfirmResponse;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.payment.service.PaymentFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentFacade paymentFacade;

    // 결제창이 닫힌 뒤 프론트가 호출한다. 프론트가 받은 결제 결과는 믿지 않고 서버가 포트원에 다시 조회해 반영한다
    @PostMapping("/{portoneId}/confirm")
    public ResponseEntity<ApiResponse<PaymentConfirmResponse>> confirmPayment(@Auth AuthUser authUser,
                                                                              @PathVariable String portoneId) {
        PaymentStatus status = paymentFacade.confirmPayment(authUser.id(), portoneId);
        return ResponseEntity.ok(ApiResponse.ok(PaymentConfirmResponse.of(portoneId, status)));
    }
}