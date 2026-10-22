package com.server.zelo.domain.paymentTest.facade;

import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.payment.facade.PaymentFacade;
import com.server.zelo.domain.payment.service.PaymentCommandService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;

@ExtendWith(MockitoExtension.class)
class PaymentFacadeTest {

    private static final String PORTONE_ID = "payment-uuid-1";

    @InjectMocks
    private PaymentFacade paymentFacade;

    @Mock
    private LockDistributeService lockDistributeService;

    @Mock
    private PaymentCommandService paymentCommandService;

    @Test
    void 결제_단위_락_안에서_결제를_확인한다() {
        // given
        // 락을 실제로 잡지 않고 넘겨받은 로직만 실행
        willAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).given(lockDistributeService).execute(eq(LockKey.confirmPayment(PORTONE_ID)), any(Runnable.class));

        // when
        paymentFacade.confirmPayment(PORTONE_ID);

        // then
        then(paymentCommandService).should().confirmPayment(PORTONE_ID);
    }
}