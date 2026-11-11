package com.server.zelo.domain.order.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.order.entity.ActorType;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderItem;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.entity.ShippingAddress;
import com.server.zelo.domain.order.repository.OrderConfirmationRepository;
import com.server.zelo.domain.order.repository.OrderRepository;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderDetailServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long ORDER_ID = 101L;

    @Mock private User user;
    @Mock private Product product;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderConfirmationRepository confirmationRepository;
    @InjectMocks private OrderService service;

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void 본인_주문은_상태와_관계없이_품목과_함께_조회한다(OrderStatus status) {
        // given
        Order order = order();
        OrderItem item = OrderItem.of(order, product, "사과", "1개", 1_000L, 3, 3_000L);
        ReflectionTestUtils.setField(order, "status", status);
        given(user.getId()).willReturn(USER_ID);
        given(orderRepository.findWithOrderItemsById(ORDER_ID)).willReturn(Optional.of(order));

        // when
        Order result = service.getOwnedOrderWithItems(USER_ID, ORDER_ID);

        // then
        assertThat(result).isSameAs(order);
        assertThat(result.getOrderItems()).containsExactly(item);
        assertThat(result.getStatus()).isEqualTo(status);
        then(orderRepository).should().findWithOrderItemsById(ORDER_ID);
        then(orderRepository).shouldHaveNoMoreInteractions();
        verifyNoInteractions(confirmationRepository, product);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void 잘못된_주문_ID는_조회_전에_입력_오류로_거부한다(Long orderId) {
        // given
        Long invalidId = orderId;

        // when
        // then
        assertThatThrownBy(() -> service.getOwnedOrderWithItems(USER_ID, invalidId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        verifyNoInteractions(orderRepository, confirmationRepository, user);
    }

    @Test
    void 주문이_없으면_접근_거부가_아닌_주문_없음_오류를_반환한다() {
        // given
        given(orderRepository.findWithOrderItemsById(ORDER_ID)).willReturn(Optional.empty());

        // when
        // then
        assertThatThrownBy(() -> service.getOwnedOrderWithItems(USER_ID, ORDER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
        then(orderRepository).should().findWithOrderItemsById(ORDER_ID);
        then(orderRepository).shouldHaveNoMoreInteractions();
        verifyNoInteractions(user, confirmationRepository);
    }

    @Test
    void 다른_사용자의_주문은_접근을_거부한다() {
        // given
        Order order = order();
        given(user.getId()).willReturn(8L);
        given(orderRepository.findWithOrderItemsById(ORDER_ID)).willReturn(Optional.of(order));

        // when
        // then
        assertThatThrownBy(() -> service.getOwnedOrderWithItems(USER_ID, ORDER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ORDER_ACCESS_DENIED));
        then(orderRepository).should().findWithOrderItemsById(ORDER_ID);
        then(orderRepository).shouldHaveNoMoreInteractions();
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, Long.MAX_VALUE})
    void 양수_주문_ID는_저장소에_그대로_전달한다(Long orderId) {
        // given
        Order order = order();
        given(user.getId()).willReturn(USER_ID);
        given(orderRepository.findWithOrderItemsById(orderId)).willReturn(Optional.of(order));

        // when
        Order result = service.getOwnedOrderWithItems(USER_ID, orderId);

        // then
        assertThat(result).isSameAs(order);
        then(orderRepository).should().findWithOrderItemsById(orderId);
    }

    private Order order() {
        return Order.of(user, "QUOTE-DETAIL-169", "사과 주문", ActorType.USER, null, 3_000L,
                ShippingAddress.of("홍길동", "010-1234-5678", "06236", "서울 강남구"));
    }
}
