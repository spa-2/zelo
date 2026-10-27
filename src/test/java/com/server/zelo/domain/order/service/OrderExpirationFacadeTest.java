package com.server.zelo.domain.order.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderExpirationFacadeTest {

    private static final Long ORDER_ID = 101L;
    private static final String PORTONE_ID = "payment-expiration-214";
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2030, 1, 2, 12, 0);

    @Mock private OrderCommandService command;
    @Mock private LockDistributeService lockService;
    @InjectMocks private OrderFacade facade;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 결제가_있으면_공통_결제_락_안에서_취소하고_처리_여부를_반환한다(boolean canceled) {
        // given
        given(command.findPortOneIdForExpiration(ORDER_ID)).willReturn(Optional.of(PORTONE_ID));
        given(command.cancelExpiredOrder(ORDER_ID, CUTOFF)).willReturn(canceled);
        given(lockService.execute(eq(LockKey.confirmPayment(PORTONE_ID)),
                org.mockito.ArgumentMatchers.<Supplier<Boolean>>any())).willAnswer(invocation -> {
                    then(command).should(never()).cancelExpiredOrder(ORDER_ID, CUTOFF);
                    Supplier<Boolean> work = invocation.getArgument(1);
                    return work.get();
                });

        // when
        boolean result = facade.cancelExpiredOrder(ORDER_ID, CUTOFF);

        // then
        assertThat(result).isEqualTo(canceled);
        then(command).should().cancelExpiredOrder(ORDER_ID, CUTOFF);
        then(lockService).should().execute(eq(LockKey.confirmPayment(PORTONE_ID)),
                org.mockito.ArgumentMatchers.<Supplier<Boolean>>any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 결제가_없으면_분산_락_없이_Command의_주문_잠금으로_처리한다(boolean canceled) {
        // given
        given(command.findPortOneIdForExpiration(ORDER_ID)).willReturn(Optional.empty());
        given(command.cancelExpiredOrder(ORDER_ID, CUTOFF)).willReturn(canceled);

        // when
        boolean result = facade.cancelExpiredOrder(ORDER_ID, CUTOFF);

        // then
        assertThat(result).isEqualTo(canceled);
        then(command).should().cancelExpiredOrder(ORDER_ID, CUTOFF);
        verifyNoInteractions(lockService);
    }

    @Test
    void 락을_얻지_못하면_취소를_호출하지_않고_예외를_전달한다() {
        // given
        given(command.findPortOneIdForExpiration(ORDER_ID)).willReturn(Optional.of(PORTONE_ID));
        BusinessException failure = new BusinessException(ErrorCode.TIMEOUT_EXCEPTION);
        given(lockService.execute(eq(LockKey.confirmPayment(PORTONE_ID)),
                org.mockito.ArgumentMatchers.<Supplier<Boolean>>any())).willThrow(failure);

        // when
        // then
        assertThatThrownBy(() -> facade.cancelExpiredOrder(ORDER_ID, CUTOFF)).isSameAs(failure);
        then(command).should(never()).cancelExpiredOrder(ORDER_ID, CUTOFF);
    }

    @Test
    void 결제번호_조회가_실패하면_락과_취소를_호출하지_않는다() {
        // given
        IllegalStateException failure = new IllegalStateException("결제 조회 실패");
        given(command.findPortOneIdForExpiration(ORDER_ID)).willThrow(failure);

        // when
        // then
        assertThatThrownBy(() -> facade.cancelExpiredOrder(ORDER_ID, CUTOFF)).isSameAs(failure);
        then(command).should(never()).cancelExpiredOrder(ORDER_ID, CUTOFF);
        verifyNoInteractions(lockService);
    }

    @Test
    void 취소_실패는_락_콜백_밖으로_전달한다() {
        // given
        given(command.findPortOneIdForExpiration(ORDER_ID)).willReturn(Optional.of(PORTONE_ID));
        IllegalStateException failure = new IllegalStateException("재고 해제 실패");
        given(command.cancelExpiredOrder(ORDER_ID, CUTOFF)).willThrow(failure);
        given(lockService.execute(eq(LockKey.confirmPayment(PORTONE_ID)),
                org.mockito.ArgumentMatchers.<Supplier<Boolean>>any())).willAnswer(invocation -> {
                    Supplier<Boolean> work = invocation.getArgument(1);
                    return work.get();
                });

        // when
        // then
        assertThatThrownBy(() -> facade.cancelExpiredOrder(ORDER_ID, CUTOFF)).isSameAs(failure);
    }
}
