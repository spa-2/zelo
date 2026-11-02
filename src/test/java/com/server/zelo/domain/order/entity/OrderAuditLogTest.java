package com.server.zelo.domain.order.entity;

import com.server.zelo.domain.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class OrderAuditLogTest {

    @Mock
    private Order order;

    @Mock
    private User user;

    @Test
    void 상태_변경_기록은_주문의_소유자_견적_바뀐_상태를_담는다() {
        // given
        given(order.getId()).willReturn(10L);
        given(order.getUser()).willReturn(user);
        given(user.getId()).willReturn(7L);
        given(order.getQuoteId()).willReturn("QUOTE-AUDIT-1");
        given(order.getStatus()).willReturn(OrderStatus.CANCELED);

        // when
        OrderAuditLog log = OrderAuditLog.stateChanged(order, OrderAuditAction.CANCELED,
                OrderStatus.PAYMENT_PENDING, ActorType.USER, 7L, null);

        // then
        assertThat(log.getOrderId()).isEqualTo(10L);
        assertThat(log.getUserId()).isEqualTo(7L);
        assertThat(log.getQuoteId()).isEqualTo("QUOTE-AUDIT-1");
        assertThat(log.getAction()).isEqualTo(OrderAuditAction.CANCELED);
        assertThat(log.getActorType()).isEqualTo(ActorType.USER);
        assertThat(log.getActorId()).isEqualTo(7L);
        assertThat(log.getPreviousStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(log.getNewStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(log.getErrorCode()).isNull();
    }

    @Test
    void 거부_기록은_주문과_상태_없이_요청한_회원과_오류_코드를_담는다() {
        // when
        OrderAuditLog log = OrderAuditLog.rejected(7L, "QUOTE-AUDIT-2", "QUOTE_EXPIRED", "견적이 만료되었습니다.");

        // then
        assertThat(log.getOrderId()).isNull();
        assertThat(log.getUserId()).isEqualTo(7L);
        assertThat(log.getQuoteId()).isEqualTo("QUOTE-AUDIT-2");
        assertThat(log.getAction()).isEqualTo(OrderAuditAction.REQUEST_REJECTED);
        assertThat(log.getActorType()).isEqualTo(ActorType.USER);
        assertThat(log.getActorId()).isEqualTo(7L);
        assertThat(log.getPreviousStatus()).isNull();
        assertThat(log.getNewStatus()).isNull();
        assertThat(log.getErrorCode()).isEqualTo("QUOTE_EXPIRED");
        assertThat(log.getReason()).isEqualTo("견적이 만료되었습니다.");
    }

    @Test
    void 사유가_500자를_넘으면_잘라서_저장한다() {
        // when
        OrderAuditLog log = OrderAuditLog.rejected(7L, null, "INVALID_INPUT", "가".repeat(600));

        // then
        assertThat(log.getReason()).hasSize(500);
    }
}
