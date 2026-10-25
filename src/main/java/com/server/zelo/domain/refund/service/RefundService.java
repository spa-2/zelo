package com.server.zelo.domain.refund.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.refund.entity.Refund;
import com.server.zelo.domain.refund.entity.RefundStatus;
import com.server.zelo.domain.refund.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class RefundService {

    private final RefundRepository refundRepository;

    public Refund createRefund(Refund refund) {
        return refundRepository.save(refund);
    }

    // 포트원 응답이 끊겨 REQUESTED로 남은 환불이 있으면 결과를 알 수 없으므로 새 요청을 막는다
    public void validateNotInProgress(Long paymentId) {
        if (refundRepository.existsByPaymentIdAndStatus(paymentId, RefundStatus.REQUESTED)) {
            throw new BusinessException(ErrorCode.REFUND_IN_PROGRESS);
        }
    }

    public void complete(Long refundId, LocalDateTime refundedAt) {
        getRefund(refundId).complete(refundedAt);
    }

    public void fail(Long refundId) {
        getRefund(refundId).fail();
    }

    private Refund getRefund(Long refundId) {
        return refundRepository.findById(refundId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND));
    }
}