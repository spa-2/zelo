package com.server.zelo.domain.payment.facade;

import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.payment.service.PaymentCommandService;
import com.server.zelo.domain.payment.service.PaymentFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentFacadeTest {

    private static final String PORTONE_ID = "payment-uuid-1";

    @InjectMocks
    private PaymentFacade paymentFacade;

    @Mock
    private LockDistributeService lockDistributeService;

    @Mock
    private PaymentCommandService paymentCommandService;

    // 락을 실제로 잡지 않고 넘겨받은 로직만 실행
    @BeforeEach
    void setUp() {
        willAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get())
                .given(lockDistributeService).execute(anyString(), any(Supplier.class));
    }

    @Test
    void 결제_단위_락_안에서_결제를_확인한다() {
        // given
        given(paymentCommandService.confirmPayment(PORTONE_ID)).willReturn(PaymentStatus.PAID);

        // when
        PaymentStatus status = paymentFacade.confirmPayment(PORTONE_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void 사용자_요청도_웹훅과_같은_결제_단위_락_안에서_확인한다() {
        // given
        given(paymentCommandService.confirmPayment(1L, PORTONE_ID)).willReturn(PaymentStatus.PAID);

        // when
        PaymentStatus status = paymentFacade.confirmPayment(1L, PORTONE_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void 확인_대기_결제가_있으면_결제_확인_후_재결제한다() {
        // given
        given(paymentCommandService.findConfirmPendingPortoneId(1L, 10L)).willReturn(Optional.of(PORTONE_ID));
        given(paymentCommandService.retryPayment(1L, 10L)).willReturn(null);

        // when
        paymentFacade.retryPayment(1L, 10L);

        // then
        then(lockDistributeService).should().execute(eq(LockKey.retryPayment(10L)), any(Supplier.class));
        then(paymentCommandService).should().confirmPayment(PORTONE_ID);
        then(paymentCommandService).should().retryPayment(1L, 10L);
    }
}