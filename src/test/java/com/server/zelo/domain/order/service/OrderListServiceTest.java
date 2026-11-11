package com.server.zelo.domain.order.service;

import com.server.zelo.domain.order.dto.request.SearchOrderCondition;
import com.server.zelo.domain.order.entity.ActorType;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.ShippingAddress;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.repository.OrderRepository;
import com.server.zelo.domain.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class OrderListServiceTest {

    private static final Long USER_ID = 7L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 22, 14, 30);

    @Mock
    private User user;

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderService orderService;

    @Test
    void 상태_필터가_없으면_본인_주문을_조회하고_목록과_페이지_정보를_변환한다() {
        // given
        Pageable sortedPage = latestPage(2, 2);
        SearchOrderCondition condition = new SearchOrderCondition(null, 2, 2);
        Order first = order(101L, "사과 주문", 3_000L, CREATED_AT, OrderStatus.PAYMENT_PENDING);
        Order second = order(100L, "당근 주문", 5_000L, CREATED_AT.minusHours(1), OrderStatus.PAID);
        given(orderRepository.findByUser_Id(sortedPage, USER_ID))
                .willReturn(new PageImpl<>(List.of(first, second), sortedPage, 7));

        // when
        Page<Order> result = orderService.getMyOrders(USER_ID, condition);

        // then
        assertThat(result.getContent()).containsExactly(first, second);
        assertThat(result.getNumber()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(2);
        assertThat(result.getTotalElements()).isEqualTo(7);
        assertThat(result.getTotalPages()).isEqualTo(4);
        then(orderRepository).should().findByUser_Id(sortedPage, USER_ID);
        then(orderRepository).shouldHaveNoMoreInteractions();
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void 상태_필터가_있으면_사용자_ID와_요청한_상태를_함께_전달한다(OrderStatus status) {
        // given
        Pageable sortedPage = latestPage(0, 20);
        SearchOrderCondition condition = new SearchOrderCondition(status, 0, 20);
        Order order = order(101L, "사과 주문", 3_000L, CREATED_AT, status);
        given(orderRepository.findByUser_IdAndStatus(sortedPage, USER_ID, status))
                .willReturn(new PageImpl<>(List.of(order), sortedPage, 1));

        // when
        Page<Order> result = orderService.getMyOrders(USER_ID, condition);

        // then
        assertThat(result.getContent()).containsExactly(order);
        assertThat(result.getTotalElements()).isEqualTo(1);
        then(orderRepository).should().findByUser_IdAndStatus(sortedPage, USER_ID, status);
        then(orderRepository).shouldHaveNoMoreInteractions();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 조회한_주문이_없으면_예외_없이_빈_목록과_페이지_정보를_반환한다(boolean filtered) {
        // given
        Pageable sortedPage = latestPage(3, 20);
        OrderStatus status = filtered ? OrderStatus.PAID : null;
        SearchOrderCondition condition = new SearchOrderCondition(status, 3, 20);
        if (filtered) {
            given(orderRepository.findByUser_IdAndStatus(sortedPage, USER_ID, status))
                    .willReturn(Page.empty(sortedPage));
        } else {
            given(orderRepository.findByUser_Id(sortedPage, USER_ID)).willReturn(Page.empty(sortedPage));
        }

        // when
        Page<Order> result = orderService.getMyOrders(USER_ID, condition);

        // then
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getNumber()).isEqualTo(3);
        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getTotalPages()).isZero();
        if (filtered) {
            then(orderRepository).should().findByUser_IdAndStatus(sortedPage, USER_ID, status);
        } else {
            then(orderRepository).should().findByUser_Id(sortedPage, USER_ID);
        }
        then(orderRepository).shouldHaveNoMoreInteractions();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 페이지_조건을_생략하면_기본값과_생성시각_ID_내림차순으로_조회한다(boolean filtered) {
        // given
        Pageable sortedPage = latestPage(0, 20);
        OrderStatus status = filtered ? OrderStatus.PAID : null;
        SearchOrderCondition condition = new SearchOrderCondition(status, null, null);
        if (filtered) {
            given(orderRepository.findByUser_IdAndStatus(sortedPage, USER_ID, status))
                    .willReturn(Page.empty(sortedPage));
        } else {
            given(orderRepository.findByUser_Id(sortedPage, USER_ID)).willReturn(Page.empty(sortedPage));
        }

        // when
        Page<Order> result = orderService.getMyOrders(USER_ID, condition);

        // then
        assertThat(result.getNumber()).isZero();
        assertThat(result.getSize()).isEqualTo(20);
        if (filtered) {
            then(orderRepository).should().findByUser_IdAndStatus(sortedPage, USER_ID, status);
        } else {
            then(orderRepository).should().findByUser_Id(sortedPage, USER_ID);
        }
        then(orderRepository).shouldHaveNoMoreInteractions();
    }

    private Pageable latestPage(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    private Order order(Long id, String name, Long totalAmount, LocalDateTime createdAt, OrderStatus status) {
        Order order = Order.of(user, "QUOTE-" + id, name, ActorType.USER, null, totalAmount, shippingAddress());
        switch (status) {
            case PAYMENT_PENDING -> { }
            case PAID -> order.pay();
            case PAYMENT_FAILED -> order.failPayment();
            case CANCELED -> order.cancel();
        }
        // DB가 채우는 ID와 생성 시각만 넣고, 응답 변환에는 실제 주문 엔티티를 사용한다.
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "createdAt", createdAt);
        return order;
    }

    private ShippingAddress shippingAddress() {
        return ShippingAddress.of("홍길동", "010-1234-5678", "06236", "서울특별시 강남구 테헤란로 123");
    }
}
