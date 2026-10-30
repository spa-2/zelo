package com.server.zelo.domain.payment.scheduler;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.payment.service.PaymentFacade;
import com.server.zelo.domain.payment.service.PaymentService;
import com.server.zelo.domain.refund.dto.response.RefundFindTarget;
import com.server.zelo.domain.refund.service.RefundFacade;
import com.server.zelo.domain.refund.service.RefundService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ReconcileSchedulerTest {

    @Mock private RefundFacade refundFacade;
    @Mock private PaymentFacade paymentFacade;
    @Mock private RefundService refundService;
    @Mock private PaymentService paymentService;
    @Mock private BusinessMetrics businessMetrics;
    @InjectMocks private ReconcileScheduler scheduler;

    @Test
    void 오래된_결제를_정리한_뒤_오래된_환불을_동기화한다() {
        // given
        given(paymentService.findConfirmPendingPortoneIds(any())).willReturn(List.of("payment-1"));
        given(paymentFacade.closeConfirmPending("payment-1")).willReturn(PaymentStatus.FAILED);
        given(refundService.findRequestedRefunds(any())).willReturn(List.of(new RefundFindTarget(10L, "payment-2")));

        // when
        scheduler.reconcile();

        // then
        then(paymentFacade).should().closeConfirmPending("payment-1");
        then(refundFacade).should().syncCancellation(10L, "payment-2");
    }

    @Test
    void 결제_정리_중_포트원이_응답하지_않으면_남은_결제와_환불을_다음_실행으로_미룬다() {
        // given
        given(paymentService.findConfirmPendingPortoneIds(any())).willReturn(List.of("payment-1", "payment-2"));
        willThrow(new BusinessException(ErrorCode.PG_TIMEOUT)).given(paymentFacade).closeConfirmPending("payment-1");

        // when
        scheduler.reconcile();

        // then
        then(paymentFacade).should(never()).closeConfirmPending("payment-2");
        then(refundService).should(never()).findRequestedRefunds(any());
    }

    @Test
    void 한_환불의_동기화가_실패해도_나머지_환불은_동기화한다() {
        // given
        given(paymentService.findConfirmPendingPortoneIds(any())).willReturn(List.of());
        given(refundService.findRequestedRefunds(any())).willReturn(List.of(
                new RefundFindTarget(10L, "payment-1"), new RefundFindTarget(11L, "payment-2")));
        willThrow(new BusinessException(ErrorCode.INVALID_REFUND_STATUS)).given(refundFacade).syncCancellation(10L, "payment-1");

        // when
        scheduler.reconcile();

        // then
        then(refundFacade).should().syncCancellation(11L, "payment-2");
    }

    @Test
    void 환불_동기화_중_포트원이_응답하지_않으면_남은_환불을_다음_실행으로_미룬다() {
        // given
        given(paymentService.findConfirmPendingPortoneIds(any())).willReturn(List.of());
        given(refundService.findRequestedRefunds(any())).willReturn(List.of(
                new RefundFindTarget(10L, "payment-1"), new RefundFindTarget(11L, "payment-2")));
        willThrow(new BusinessException(ErrorCode.PG_TIMEOUT)).given(refundFacade).syncCancellation(10L, "payment-1");

        // when
        scheduler.reconcile();

        // then
        then(refundFacade).should(never()).syncCancellation(11L, "payment-2");
    }
}