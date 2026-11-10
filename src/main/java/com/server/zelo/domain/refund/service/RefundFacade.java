package com.server.zelo.domain.refund.service;

import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.refund.dto.request.CreateRefundRequest;
import com.server.zelo.domain.refund.dto.response.CreateRefundResponse;
import com.server.zelo.domain.refund.service.RefundCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RefundFacade {

    private final RefundCommandService refundCommandService;
    private final LockDistributeService lockDistributeService;

    public CreateRefundResponse createRefund(Long userId, Long orderId, CreateRefundRequest request) {
        return lockDistributeService.execute(LockKey.refund(orderId),
                () -> refundCommandService.createRefund(userId, orderId, request.reason(), request.toCancelQuantities()));
    }

    public void syncCancellation(Long orderId, String portoneId) {
        lockDistributeService.execute(LockKey.refund(orderId),
                () -> refundCommandService.syncCancellation(orderId, portoneId));
    }

}