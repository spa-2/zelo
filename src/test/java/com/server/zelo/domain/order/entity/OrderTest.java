package com.server.zelo.domain.order.entity;

import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class OrderTest {

    @Mock
    private User user;

    @Mock
    private ChatRoom chatRoom;

    /**
     * 사용자와 에이전트 주문 모두 전달한 주문 정보를 보존하고 결제 대기 상태로 시작한다.
     */
    @ParameterizedTest
    @EnumSource(value = ActorType.class, names = {"USER", "AGENT"})
    void 주문을_생성하면_요청_정보를_보존하고_결제_대기_상태가_된다(ActorType requesterType) {
        // given
        String quoteId = "QUOTE-ORDER-001";
        String orderName = "김치볶음밥 재료";
        Long totalAmount = 21_500L;

        // when
        Order order = Order.of(user, quoteId, orderName, requesterType, chatRoom, totalAmount);

        // then
        assertThat(order.getUser()).isSameAs(user);
        assertThat(order.getQuoteId()).isEqualTo(quoteId);
        assertThat(order.getOrderName()).isEqualTo(orderName);
        assertThat(order.getRequesterType()).isEqualTo(requesterType);
        assertThat(order.getChatRoom()).isSameAs(chatRoom);
        assertThat(order.getTotalAmount()).isEqualTo(totalAmount);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getOrderItems()).isEmpty();
    }

    /**
     * 웹에서 생성하는 주문은 채팅방 없이도 생성할 수 있다.
     */
    @Test
    void 웹_주문은_채팅방_없이_생성할_수_있다() {
        // given
        String quoteId = "QUOTE-WEB-001";

        // when
        Order order = Order.of(user, quoteId, "웹 주문", ActorType.USER, null, 8_900L);

        // then
        assertThat(order.getChatRoom()).isNull();
        assertThat(order.getUser()).isSameAs(user);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }
}
