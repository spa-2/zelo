package com.server.zelo.domain.refund.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.refund.entity.Refund;
import com.server.zelo.domain.refund.entity.RefundStatus;
import com.server.zelo.domain.refund.entity.RefundType;
import com.server.zelo.domain.refund.repository.RefundRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    private static final Long PAYMENT_ID = 1L;
    private static final Long REFUND_ID = 10L;

    @InjectMocks
    private RefundService refundService;

    @Mock
    private RefundRepository refundRepository;

    @Test
    void 진행_중인_환불이_있으면_예외가_발생한다() {
        // given
        given(refundRepository.existsByPaymentIdAndStatus(PAYMENT_ID, RefundStatus.REQUESTED)).willReturn(true);

        // when // then
        assertThatThrownBy(() -> refundService.validateNotInProgress(PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REFUND_IN_PROGRESS);
    }

    @Test
    void 진행_중인_환불이_없으면_통과한다() {
        // given
        given(refundRepository.existsByPaymentIdAndStatus(PAYMENT_ID, RefundStatus.REQUESTED)).willReturn(false);

        // when // then
        assertThatCode(() -> refundService.validateNotInProgress(PAYMENT_ID)).doesNotThrowAnyException();
    }

    @Test
    void 환불을_완료하면_완료_상태와_완료_시각이_남는다() {
        // given
        Refund refund = Refund.of(PAYMENT_ID, RefundType.FULL, "단순 변심");
        LocalDateTime refundedAt = LocalDateTime.now();
        given(refundRepository.findById(REFUND_ID)).willReturn(Optional.of(refund));

        // when
        refundService.complete(REFUND_ID, refundedAt);

        // then
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(refund.getRefundedAt()).isEqualTo(refundedAt);
    }

    @Test
    void 환불을_실패_처리하면_실패_상태가_된다() {
        // given
        Refund refund = Refund.of(PAYMENT_ID, RefundType.FULL, "단순 변심");
        given(refundRepository.findById(REFUND_ID)).willReturn(Optional.of(refund));

        // when
        refundService.fail(REFUND_ID);

        // then
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
    }

    @Test
    void 환불이_없으면_예외가_발생한다() {
        // given
        given(refundRepository.findById(REFUND_ID)).willReturn(Optional.empty());

        // when // then
        assertThatThrownBy(() -> refundService.fail(REFUND_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REFUND_NOT_FOUND);
    }

    @Test
    void 진행_중인_환불을_찾는다() {
        // given
        Refund refund = Refund.of(PAYMENT_ID, RefundType.FULL, "단순 변심");
        given(refundRepository.findByPaymentIdAndStatus(PAYMENT_ID, RefundStatus.REQUESTED))
                .willReturn(Optional.of(refund));

        // when // then
        assertThat(refundService.findRequestedRefund(PAYMENT_ID)).containsSame(refund);
    }
}