package com.server.zelo.domain.refund.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.refund.entity.Refund;
import com.server.zelo.domain.refund.entity.RefundStatus;
import com.server.zelo.domain.refund.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RefundService {

    private final RefundRepository refundRepository;

    public Refund createRefund(Refund refund) {
        return refundRepository.save(refund);
    }

    @Transactional
    public Optional<Refund> findLatestRefund(Long paymentId) {
        return refundRepository.findTopByPaymentIdOrderByIdDesc(paymentId);
    }

    public void validateNotInProgress(Long paymentId) {
        if (refundRepository.existsByPaymentIdAndStatus(paymentId, RefundStatus.REQUESTED)) {
            throw new BusinessException(ErrorCode.REFUND_IN_PROGRESS);
        }
    }

    // 부분 환불 뒤 포트원에 넘길 취소 가능 잔액을 계산하려고 완료된 환불 금액을 더한다
    public long calculateRefundedAmount(Long paymentId) {
        return refundRepository.findAllByPaymentIdAndStatus(paymentId, RefundStatus.COMPLETED).stream()
                .mapToLong(Refund::getItemsAmount)
                .sum();
    }

    public void complete(Long refundId, LocalDateTime refundedAt) {
        getRefund(refundId).complete(refundedAt);
    }

    public void fail(Long refundId) {
        getRefund(refundId).fail();
    }

    public Optional<Refund> findRequestedRefund(Long paymentId) {
        return refundRepository.findByPaymentIdAndStatus(paymentId, RefundStatus.REQUESTED);
    }

    private Refund getRefund(Long refundId) {
        return refundRepository.findById(refundId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND));
    }
}