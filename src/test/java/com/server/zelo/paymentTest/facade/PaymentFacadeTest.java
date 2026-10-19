package com.server.zelo.paymentTest.facade;

import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.payment.dto.response.CreatePaymentResponse;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.payment.facade.PaymentFacade;
import com.server.zelo.domain.payment.service.PaymentCommandService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class PaymentFacadeTest {

    @InjectMocks
    private PaymentFacade paymentFacade;

    @Mock
    private LockDistributeService lockDistributeService;

    @Mock
    private PaymentCommandService paymentCommandService;

    @Test
    @SuppressWarnings("unchecked")
    void 주문_단위_락_안에서_결제를_생성한다() {
        // given
        CreatePaymentResponse response = new CreatePaymentResponse(
                1L, 10L, "payment-uuid-1", "김치볶음밥 재료", 32_000L, PaymentStatus.READY);
        given(paymentCommandService.createPayment(1L, 10L)).willReturn(response);
        // 락을 실제로 잡지 않고 넘겨받은 로직만 실행
        given(lockDistributeService.execute(eq(LockKey.createPayment(10L)), any(Supplier.class)))
                .willAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(1)).get());

        // when
        CreatePaymentResponse result = paymentFacade.createPayment(1L, 10L);

        // then
        assertThat(result).isEqualTo(response);
        then(paymentCommandService).should().createPayment(1L, 10L);
    }
}
