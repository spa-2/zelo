package com.server.zelo.domain.refund.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.common.auth.Auth;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.domain.refund.dto.request.CreateRefundRequest;
import com.server.zelo.domain.refund.dto.response.CreateRefundResponse;
import com.server.zelo.domain.refund.service.RefundFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders/{orderId}/refund")
public class RefundController {

    private final RefundFacade refundFacade;

    @PostMapping
    public ResponseEntity<ApiResponse<CreateRefundResponse>> createRefund(@Auth AuthUser authUser,
                                                                          @PathVariable Long orderId,
                                                                          @Valid @RequestBody CreateRefundRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(refundFacade.createRefund(authUser.id(), orderId, request)));
    }
}