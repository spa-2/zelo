package com.server.zelo.domain.refund.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.refund.dto.response.OrderRefundAmount;
import com.server.zelo.domain.refund.dto.response.RefundFindTarget;
import com.server.zelo.domain.refund.entity.Refund;
import com.server.zelo.domain.refund.entity.RefundStatus;
import com.server.zelo.domain.refund.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RefundService {
    //스케줄러 스레드를 같이 공유해 20건씩 처리 하는 상수
    private static final int STALE_REQUESTED_BATCH_SIZE = 20;
    private final RefundRepository refundRepository;

    public Refund createRefund(Refund refund) {
        return refundRepository.save(refund);
    }

    @Transactional
    public Optional<Refund> findLatestRefund(Long paymentId) {
        return refundRepository.findTopByPaymentIdOrderByIdDesc(paymentId);
    }

    @Transactional(readOnly = true)
    public Map<Long,Long> findRefundedAmounts(Collection<Long> orderIds){
        if(orderIds.isEmpty()) {
            return Map.of();
        }
        return refundRepository.findRefundedAmountsByOrderIds(orderIds,RefundStatus.COMPLETED)
                .stream()
                .collect(Collectors.toMap(OrderRefundAmount::orderId,
                        OrderRefundAmount::refundedAmount));
    }

    @Transactional(readOnly = true)
    public List<RefundFindTarget> findRequestedRefunds(LocalDateTime cutoff) {
        return refundRepository.findTargets(RefundStatus.REQUESTED, cutoff,
                PageRequest.of(0, STALE_REQUESTED_BATCH_SIZE));
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