package com.server.zelo.domain.order.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.order.dto.response.CancelOrderResponse;
import com.server.zelo.domain.order.entity.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderCancellationFacadeTest {

    private static final Long USER_ID = 7L;
    private static final Long ORDER_ID = 101L;
    private static final String PORTONE_ID = "payment-cancel-170";
    private static final CancelOrderResponse RESPONSE = new CancelOrderResponse(ORDER_ID, OrderStatus.CANCELED);

    @Mock private OrderCommandService command;
    @Mock private LockDistributeService lockService;

    private OrderFacade facade;

    @BeforeEach
    void setUp() {
        facade = new OrderFacade(command, lockService);
    }

    @Test
    void 사전조회_후_결제확인과_같은_락_안에서_취소한다() {
        // given
        AtomicBoolean locked = new AtomicBoolean(false);
        given(command.findPaymentIdForCancellation(USER_ID, ORDER_ID)).willAnswer(invocation -> {
            assertThat(locked).isFalse();
            return Optional.of(PORTONE_ID);
        });
        given(lockService.execute(eq(LockKey.confirmPayment(PORTONE_ID)),
                org.mockito.ArgumentMatchers.<Supplier<CancelOrderResponse>>any()))
                .willAnswer(invocation -> {
                    locked.set(true);
                    try {
                        Supplier<CancelOrderResponse> work = invocation.getArgument(1);
                        return work.get();
                    } finally {
                        locked.set(false);
                    }
                });
        given(command.cancelBeforePayment(USER_ID, ORDER_ID)).willAnswer(invocation -> {
            assertThat(locked).isTrue();
            return RESPONSE;
        });

        // when
        CancelOrderResponse result = facade.cancelBeforePayment(USER_ID, ORDER_ID);

        // then
        assertThat(result).isSameAs(RESPONSE);
        assertThat(locked).isFalse();
        then(command).should().findPaymentIdForCancellation(USER_ID, ORDER_ID);
        then(command).should().cancelBeforePayment(USER_ID, ORDER_ID);
        then(lockService).should().execute(eq(LockKey.confirmPayment(PORTONE_ID)),
                org.mockito.ArgumentMatchers.<Supplier<CancelOrderResponse>>any());
    }

    @Test
    void 결제정보가_없는_기존_주문은_Command의_주문잠금으로_취소한다() {
        // given
        given(command.findPaymentIdForCancellation(USER_ID, ORDER_ID)).willReturn(Optional.empty());
        given(command.cancelBeforePayment(USER_ID, ORDER_ID)).willReturn(RESPONSE);

        // when
        CancelOrderResponse result = facade.cancelBeforePayment(USER_ID, ORDER_ID);

        // then
        assertThat(result).isSameAs(RESPONSE);
        then(command).should().cancelBeforePayment(USER_ID, ORDER_ID);
        verifyNoInteractions(lockService);
    }

    @Test
    void 사전검증에서_거부되면_락과_취소를_실행하지_않는다() {
        // given
        BusinessException failure = new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        given(command.findPaymentIdForCancellation(USER_ID, ORDER_ID)).willThrow(failure);

        // when
        // then
        assertThatThrownBy(() -> facade.cancelBeforePayment(USER_ID, ORDER_ID)).isSameAs(failure);
        verifyNoInteractions(lockService);
        then(command).should(never()).cancelBeforePayment(any(), any());
    }

    @Test
    void 락_획득에_실패하면_취소를_실행하지_않는다() {
        // given
        given(command.findPaymentIdForCancellation(USER_ID, ORDER_ID)).willReturn(Optional.of(PORTONE_ID));
        BusinessException failure = new BusinessException(ErrorCode.TIMEOUT_EXCEPTION);
        given(lockService.execute(eq(LockKey.confirmPayment(PORTONE_ID)),
                org.mockito.ArgumentMatchers.<Supplier<CancelOrderResponse>>any())).willThrow(failure);

        // when
        // then
        assertThatThrownBy(() -> facade.cancelBeforePayment(USER_ID, ORDER_ID)).isSameAs(failure);
        then(command).should(never()).cancelBeforePayment(any(), any());
    }

    @Test
    void 락_획득_후_재검증_실패를_성공으로_바꾸지_않는다() {
        // given
        given(command.findPaymentIdForCancellation(USER_ID, ORDER_ID)).willReturn(Optional.of(PORTONE_ID));
        given(lockService.execute(eq(LockKey.confirmPayment(PORTONE_ID)),
                org.mockito.ArgumentMatchers.<Supplier<CancelOrderResponse>>any()))
                .willAnswer(invocation -> {
                    Supplier<CancelOrderResponse> work = invocation.getArgument(1);
                    return work.get();
                });
        BusinessException failure = new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        given(command.cancelBeforePayment(USER_ID, ORDER_ID)).willThrow(failure);

        // when
        // then
        assertThatThrownBy(() -> facade.cancelBeforePayment(USER_ID, ORDER_ID)).isSameAs(failure);
        then(command).should().cancelBeforePayment(USER_ID, ORDER_ID);
    }
}
