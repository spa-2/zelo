package com.server.zelo.domain.order.scheduler;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.order.service.OrderFacade;
import com.server.zelo.domain.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderExpirationSchedulerTest {

    @Mock private OrderFacade orderFacade;
    @Mock private OrderService orderService;
    @InjectMocks private OrderExpirationScheduler scheduler;

    @Test
    void 현재_시각에서_30분_전의_동일한_기준으로_각_주문을_처리한다() {
        // given
        given(orderService.findExpiredOrderIds(any())).willReturn(List.of(101L, 102L));
        LocalDateTime before = LocalDateTime.now().minusMinutes(30);

        // when
        scheduler.cancelExpiredOrders();
        LocalDateTime after = LocalDateTime.now().minusMinutes(30);

        // then
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        then(orderService).should().findExpiredOrderIds(cutoff.capture());
        assertThat(cutoff.getValue()).isBetween(before, after);
        then(orderFacade).should().cancelExpiredOrder(101L, cutoff.getValue());
        then(orderFacade).should().cancelExpiredOrder(102L, cutoff.getValue());
        then(orderFacade).shouldHaveNoMoreInteractions();
    }

    @Test
    void 만료_후보가_없으면_취소를_호출하지_않는다() {
        // given
        given(orderService.findExpiredOrderIds(any())).willReturn(List.of());

        // when
        scheduler.cancelExpiredOrders();

        // then
        verifyNoInteractions(orderFacade);
    }

    @Test
    void 한_주문이_실패해도_뒤의_주문을_계속_처리한다() {
        // given
        given(orderService.findExpiredOrderIds(any())).willReturn(List.of(101L, 102L, 103L));
        given(orderFacade.cancelExpiredOrder(eq(102L), any()))
                .willThrow(new IllegalStateException("재고 해제 실패"));

        // when
        // then
        assertThatCode(scheduler::cancelExpiredOrders).doesNotThrowAnyException();
        then(orderFacade).should().cancelExpiredOrder(eq(101L), any());
        then(orderFacade).should().cancelExpiredOrder(eq(102L), any());
        then(orderFacade).should().cancelExpiredOrder(eq(103L), any());
    }

    @Test
    void 락_획득에_실패해도_다음_주문을_처리한다() {
        // given
        given(orderService.findExpiredOrderIds(any())).willReturn(List.of(101L, 102L));
        given(orderFacade.cancelExpiredOrder(eq(101L), any()))
                .willThrow(new BusinessException(ErrorCode.TIMEOUT_EXCEPTION));

        // when
        // then
        assertThatCode(scheduler::cancelExpiredOrders).doesNotThrowAnyException();
        then(orderFacade).should().cancelExpiredOrder(eq(102L), any());
    }
}
