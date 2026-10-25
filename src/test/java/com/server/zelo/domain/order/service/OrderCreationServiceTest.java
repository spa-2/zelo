package com.server.zelo.domain.order.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.model.OrderConfirmation;
import com.server.zelo.domain.order.repository.OrderConfirmationRepository;
import com.server.zelo.domain.order.repository.OrderRepository;
import com.server.zelo.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderCreationServiceTest {

    private static final Long USER_ID = 7L;
    private static final String QUOTE_ID = "QUOTE-161";
    private static final String CONFIRMATION_ID = "confirmation-161";

    @Mock private OrderRepository orderRepository;
    @Mock private OrderConfirmationRepository confirmationRepository;
    @Mock private Order order;
    @Mock private User user;

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orderRepository, confirmationRepository);
    }

    @Test
    void 사용자와_견적이_일치하는_확인정보를_반환한다() {
        // given
        OrderConfirmation confirmation = new OrderConfirmation(USER_ID, 10L, QUOTE_ID);
        given(confirmationRepository.findById(CONFIRMATION_ID)).willReturn(Optional.of(confirmation));

        // when
        OrderConfirmation result = service.validateConfirmation(USER_ID, QUOTE_ID, CONFIRMATION_ID);

        // then
        assertThat(result).isSameAs(confirmation);
    }

    @Test
    void 확인정보가_없거나_만료되면_INVALID_INPUT이다() {
        // given
        given(confirmationRepository.findById(CONFIRMATION_ID)).willReturn(Optional.empty());

        // when
        // then
        assertThatThrownBy(() -> service.validateConfirmation(USER_ID, QUOTE_ID, CONFIRMATION_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void 다른_사용자의_확인정보는_NO_AUTHORITY이다() {
        // given
        given(confirmationRepository.findById(CONFIRMATION_ID))
                .willReturn(Optional.of(new OrderConfirmation(99L, 10L, QUOTE_ID)));

        // when
        // then
        assertThatThrownBy(() -> service.validateConfirmation(USER_ID, QUOTE_ID, CONFIRMATION_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.NO_AUTHORITY));
    }

    @Test
    void 다른_견적의_확인정보는_INVALID_INPUT이다() {
        // given
        given(confirmationRepository.findById(CONFIRMATION_ID))
                .willReturn(Optional.of(new OrderConfirmation(USER_ID, 10L, "OTHER-QUOTE")));

        // when
        // then
        assertThatThrownBy(() -> service.validateConfirmation(USER_ID, QUOTE_ID, CONFIRMATION_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void 사용자와_견적이_모두_다르면_소유권_오류를_우선한다() {
        // given
        given(confirmationRepository.findById(CONFIRMATION_ID))
                .willReturn(Optional.of(new OrderConfirmation(99L, 10L, "OTHER-QUOTE")));

        // when
        // then
        assertThatThrownBy(() -> service.validateConfirmation(USER_ID, QUOTE_ID, CONFIRMATION_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.NO_AUTHORITY));
    }

    @Test
    void 같은_견적의_주문이_없으면_빈_결과를_반환한다() {
        // given
        given(orderRepository.findByQuoteId(QUOTE_ID)).willReturn(Optional.empty());

        // when
        Optional<Order> result = service.findOwnedOrderByQuoteId(USER_ID, QUOTE_ID);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void 본인_견적의_기존_주문을_반환한다() {
        // given
        given(orderRepository.findByQuoteId(QUOTE_ID)).willReturn(Optional.of(order));
        given(order.getUser()).willReturn(user);
        given(user.getId()).willReturn(USER_ID);

        // when
        Optional<Order> result = service.findOwnedOrderByQuoteId(USER_ID, QUOTE_ID);

        // then
        assertThat(result).containsSame(order);
    }

    @Test
    void 기존_주문이_다른_사용자_소유이면_반환하지_않는다() {
        // given
        given(orderRepository.findByQuoteId(QUOTE_ID)).willReturn(Optional.of(order));
        given(order.getUser()).willReturn(user);
        given(user.getId()).willReturn(99L);

        // when
        // then
        assertThatThrownBy(() -> service.findOwnedOrderByQuoteId(USER_ID, QUOTE_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.NO_AUTHORITY));
    }

    @Test
    void 결제_대기_주문이_있으면_신규_주문을_거부한다() {
        // given
        given(orderRepository.existsByUser_IdAndStatus(USER_ID, OrderStatus.PAYMENT_PENDING)).willReturn(true);

        // when
        // then
        assertThatThrownBy(() -> service.validateNoPendingOrder(USER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.PENDING_ORDER_EXISTS));
    }

    @Test
    void 결제_대기_주문이_없으면_통과한다() {
        // given
        given(orderRepository.existsByUser_IdAndStatus(USER_ID, OrderStatus.PAYMENT_PENDING)).willReturn(false);

        // when
        // then
        assertThatCode(() -> service.validateNoPendingOrder(USER_ID)).doesNotThrowAnyException();
    }

    @Test
    void 주문_저장_결과를_그대로_반환한다() {
        // given
        given(orderRepository.save(order)).willReturn(order);

        // when
        Order result = service.createOrder(order);

        // then
        assertThat(result).isSameAs(order);
        then(orderRepository).should().save(order);
    }

    @Test
    void 확인정보는_트랜잭션_커밋_후에만_삭제한다() {
        // given
        OrderTestTransactionManager manager = new OrderTestTransactionManager();
        TransactionTemplate template = new TransactionTemplate(manager);
        willAnswer(invocation -> {
            assertThat(manager.events).containsExactly("begin", "commit");
            return null;
        }).given(confirmationRepository).deleteById(CONFIRMATION_ID);

        // when
        template.executeWithoutResult(status -> {
            service.deleteConfirmationAfterCommit(CONFIRMATION_ID);
            then(confirmationRepository).should(never()).deleteById(anyString());
        });

        // then
        then(confirmationRepository).should().deleteById(CONFIRMATION_ID);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    @Test
    void 롤백되면_구매_확인정보를_남겨_재시도할_수_있다() {
        // given
        OrderTestTransactionManager manager = new OrderTestTransactionManager();
        TransactionTemplate template = new TransactionTemplate(manager);
        BusinessException failure = new BusinessException(ErrorCode.INSUFFICIENT_STOCK);

        // when
        // then
        assertThatThrownBy(() -> template.executeWithoutResult(status -> {
            service.deleteConfirmationAfterCommit(CONFIRMATION_ID);
            throw failure;
        })).isSameAs(failure);
        assertThat(manager.events).containsExactly("begin", "rollback");
        verifyNoInteractions(confirmationRepository);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    @Test
    void 커밋_후_Redis_삭제_실패가_주문_성공_응답을_실패로_바꾸지_않는다() {
        // given
        OrderTestTransactionManager manager = new OrderTestTransactionManager();
        TransactionTemplate template = new TransactionTemplate(manager);
        willThrow(new RedisConnectionFailureException("테스트 삭제 실패"))
                .given(confirmationRepository).deleteById(CONFIRMATION_ID);

        // when
        // then
        assertThatCode(() -> template.executeWithoutResult(status ->
                service.deleteConfirmationAfterCommit(CONFIRMATION_ID))).doesNotThrowAnyException();
        assertThat(manager.events).containsExactly("begin", "commit");
        then(confirmationRepository).should().deleteById(CONFIRMATION_ID);
    }
}
