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

    private final LockDistributeService lockDistributeService;
    private final RefundCommandService refundCommandService;

    public CreateRefundResponse createRefund(Long userId, Long orderId, CreateRefundRequest request) {
        return lockDistributeService.execute(LockKey.refund(orderId),
                () -> refundCommandService.createRefund(userId, orderId, request.reason()));
    }
}