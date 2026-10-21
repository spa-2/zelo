package com.server.zelo.domain.order.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.order.entity.ActorType;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderItem;
import com.server.zelo.domain.order.repository.OrderRepository;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private User user;

    @Mock
    private Product product;

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderService orderService;

    @Test
    void 주문_상품을_포함한_조회_결과를_반환한다() {
        // given
        Long orderId = 101L;
        Order order = Order.of(user, "QUOTE-LOOKUP", "사과 주문", ActorType.USER, null, 3_000L);
        OrderItem item = OrderItem.of(order, product, "사과", "1개", 1_000L, 3, 3_000L);
        given(orderRepository.findWithOrderItemsById(orderId)).willReturn(Optional.of(order));

        // when
        Order result = orderService.getOrderWithItems(orderId);

        // then
        assertThat(result).isSameAs(order);
        assertThat(result.getOrderItems()).containsExactly(item);
        then(orderRepository).should().findWithOrderItemsById(orderId);
    }

    @Test
    void 주문이_없으면_주문_없음_예외가_발생한다() {
        // given
        Long orderId = 404L;
        given(orderRepository.findWithOrderItemsById(orderId)).willReturn(Optional.empty());

        // when
        // then
        assertThatThrownBy(() -> orderService.getOrderWithItems(orderId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
    }

    @Test
    void 주문_잠금_조회_후_상품을_함께_조회해_반환한다() {
        // given
        Long orderId = 101L;
        Order order = Order.of(user, "QUOTE-LOCK-LOOKUP", "사과 주문", ActorType.USER, null, 3_000L);
        OrderItem item = OrderItem.of(order, product, "사과", "1개", 1_000L, 3, 3_000L);
        given(orderRepository.findForUpdateById(orderId)).willReturn(Optional.of(order));
        given(orderRepository.findWithOrderItemsById(orderId)).willReturn(Optional.of(order));

        // when
        Order result = orderService.getOrderWithItemsForUpdate(orderId);

        // then
        assertThat(result).isSameAs(order);
        assertThat(result.getOrderItems()).containsExactly(item);
        InOrder queryOrder = inOrder(orderRepository);
        queryOrder.verify(orderRepository).findForUpdateById(orderId);
        queryOrder.verify(orderRepository).findWithOrderItemsById(orderId);
    }

    @Test
    void 잠금_조회에서_주문이_없으면_상품_조회_없이_예외를_던진다() {
        // given
        Long orderId = 404L;
        given(orderRepository.findForUpdateById(orderId)).willReturn(Optional.empty());

        // when
        // then
        assertThatThrownBy(() -> orderService.getOrderWithItemsForUpdate(orderId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
        then(orderRepository).should(never()).findWithOrderItemsById(orderId);
    }
}
