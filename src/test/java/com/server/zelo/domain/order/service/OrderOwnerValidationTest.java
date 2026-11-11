package com.server.zelo.domain.order.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class OrderOwnerValidationTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderService orderService;

    @Test
    void 본인_주문이면_예외_없이_통과한다() {
        // given
        Long orderId = 101L;
        Long userId = 7L;
        given(orderRepository.existsByIdAndUser_Id(orderId, userId)).willReturn(true);

        // when
        // then
        assertThatCode(() -> orderService.validateOwner(orderId, userId))
                .doesNotThrowAnyException();
        then(orderRepository).should().existsByIdAndUser_Id(orderId, userId);
        then(orderRepository).shouldHaveNoMoreInteractions();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "다른 사용자의 주문, 101, 8",
            "존재하지 않는 주문, 999, 7"
    })
    void 소유권이_확인되지_않으면_존재_여부를_추가_조회하지_않고_같은_접근_거부_예외를_던진다(
            String scenario, Long orderId, Long userId) {
        // given
        // 두 상황 모두 소유권 조회 결과는 false이며, 서비스는 이 둘을 구분하지 않는다.
        given(orderRepository.existsByIdAndUser_Id(orderId, userId)).willReturn(false);

        // when
        // then
        assertThatThrownBy(() -> orderService.validateOwner(orderId, userId))
                .as(scenario)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ORDER_ACCESS_DENIED);
                    assertThat(exception.getMessage()).isEqualTo(ErrorCode.ORDER_ACCESS_DENIED.getMessage());
                    assertThat(exception.getErrorCode().getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });
        then(orderRepository).should().existsByIdAndUser_Id(orderId, userId);
        then(orderRepository).shouldHaveNoMoreInteractions();
    }
}
