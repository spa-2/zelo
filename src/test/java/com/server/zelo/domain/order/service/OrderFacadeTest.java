package com.server.zelo.domain.order.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.order.dto.request.CreateOrderRequest;
import com.server.zelo.domain.order.model.CreateOrderResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderFacadeTest {

    private static final Long USER_ID = 7L;
    private static final CreateOrderRequest REQUEST = new CreateOrderRequest("QUOTE-161", "confirmation-161");

    @Mock private OrderCommandService command;
    @Mock private LockDistributeService lockService;

    private OrderFacade facade;

    @BeforeEach
    void setUp() {
        facade = new OrderFacade(command, lockService);
    }

    @Test
    void 사용자별_주문_생성_락_안에서_Command를_호출하고_결과를_반환한다() {
        // given
        CreateOrderResult expected = new CreateOrderResult(null, true);
        given(command.createOrder(USER_ID, REQUEST)).willReturn(expected);
        given(lockService.execute(eq(LockKey.createOrder(USER_ID)),
                org.mockito.ArgumentMatchers.<Supplier<CreateOrderResult>>any()))
                .willAnswer(invocation -> {
                    Supplier<CreateOrderResult> work = invocation.getArgument(1);
                    return work.get();
                });

        // when
        CreateOrderResult result = facade.createOrder(USER_ID, REQUEST);

        // then
        assertThat(result).isSameAs(expected);
        then(command).should().createOrder(USER_ID, REQUEST);
        then(lockService).should().execute(eq(LockKey.createOrder(USER_ID)),
                org.mockito.ArgumentMatchers.<Supplier<CreateOrderResult>>any());
    }

    @Test
    void 락을_얻지_못하면_Command를_호출하지_않는다() {
        // given
        BusinessException failure = new BusinessException(ErrorCode.TIMEOUT_EXCEPTION);
        given(lockService.execute(eq(LockKey.createOrder(USER_ID)),
                org.mockito.ArgumentMatchers.<Supplier<CreateOrderResult>>any())).willThrow(failure);

        // when
        // then
        assertThatThrownBy(() -> facade.createOrder(USER_ID, REQUEST)).isSameAs(failure);
        verifyNoInteractions(command);
    }

    @Test
    void 사용자마다_주문_생성_락_키가_다르다() {
        // given
        Long otherUserId = 8L;

        // when
        String first = LockKey.createOrder(USER_ID);
        String second = LockKey.createOrder(otherUserId);

        // then
        assertThat(first).isNotEqualTo(second);
        assertThat(LockKey.createOrder(USER_ID)).isEqualTo(first);
    }
}
