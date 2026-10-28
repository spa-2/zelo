package com.server.zelo.domain.payment.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.common.auth.Auth;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.domain.payment.dto.request.CreateWithPaymentMethodRequest;
import com.server.zelo.domain.payment.dto.response.PaymentConfirmResponse;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.payment.service.PaymentFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    // 등록한 카드로 결제창 없이 결제한다. 응답은 결제 확인과 같은 형식이라 프론트가 같은 방식으로 처리한다
    @PostMapping("/{portoneId}/payment-method")
    public ResponseEntity<ApiResponse<PaymentConfirmResponse>> payWithPaymentMethod(
            @Auth AuthUser authUser,
            @PathVariable String portoneId,
            @Valid @RequestBody CreateWithPaymentMethodRequest request) {
        PaymentStatus status = paymentFacade.payWithPaymentMethod(authUser.id(), portoneId, request.paymentMethodId());
        return ResponseEntity.ok(ApiResponse.ok(PaymentConfirmResponse.of(portoneId, status)));
    }
}