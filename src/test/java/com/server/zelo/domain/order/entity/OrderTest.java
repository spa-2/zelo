package com.server.zelo.domain.order.entity;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.entity.UserAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        ShippingAddress shippingAddress = shippingAddress();

        // when
        Order order = Order.of(user, quoteId, orderName, requesterType, chatRoom, totalAmount, shippingAddress);

        // then
        assertThat(order.getUser()).isSameAs(user);
        assertThat(order.getQuoteId()).isEqualTo(quoteId);
        assertThat(order.getOrderName()).isEqualTo(orderName);
        assertThat(order.getRequesterType()).isEqualTo(requesterType);
        assertThat(order.getChatRoom()).isSameAs(chatRoom);
        assertThat(order.getTotalAmount()).isEqualTo(totalAmount);
        assertThat(order.getShippingAddress().getRecipientName()).isEqualTo(shippingAddress.getRecipientName());
        assertThat(order.getShippingAddress().getRecipientPhone()).isEqualTo(shippingAddress.getRecipientPhone());
        assertThat(order.getShippingAddress().getZipCode()).isEqualTo(shippingAddress.getZipCode());
        assertThat(order.getShippingAddress().getAddress()).isEqualTo(shippingAddress.getAddress());
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
        Order order = Order.of(user, quoteId, "웹 주문", ActorType.USER, null, 8_900L, shippingAddress());

        // then
        assertThat(order.getChatRoom()).isNull();
        assertThat(order.getUser()).isSameAs(user);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @Test
    void 배송지_없이_주문을_생성하면_INVALID_INPUT_예외가_발생한다() {
        // given
        ShippingAddress missingAddress = null;

        // when
        // then
        assertThatThrownBy(() -> Order.of(
                user, "QUOTE-NO-ADDRESS", "배송지 없는 주문", ActorType.USER, null, 8_900L, missingAddress))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void 회원_배송지를_수정한_뒤_결제해도_주문_배송지는_주문_시점의_값을_유지한다() {
        // given
        UserAddress defaultAddress = UserAddress.of(
                user, "집", "홍길동", "010-1234-5678", "06236", "서울특별시 강남구 테헤란로 123", true);
        ShippingAddress snapshot = ShippingAddress.of(
                defaultAddress.getRecipientName(), defaultAddress.getRecipientPhone(),
                defaultAddress.getZipCode(), defaultAddress.getAddress());
        Order order = Order.of(
                user, "QUOTE-ADDRESS-SNAPSHOT", "사과 주문", ActorType.USER, null, 8_900L, snapshot);

        // when
        defaultAddress.update("회사", "김철수", "010-9876-5432", "48058", "부산광역시 해운대구 센텀중앙로 90");
        order.pay();

        // then
        assertThat(defaultAddress.getRecipientName()).isEqualTo("김철수");
        assertThat(defaultAddress.getRecipientPhone()).isEqualTo("010-9876-5432");
        assertThat(defaultAddress.getZipCode()).isEqualTo("48058");
        assertThat(defaultAddress.getAddress()).isEqualTo("부산광역시 해운대구 센텀중앙로 90");
        assertThat(order.getShippingAddress().getRecipientName()).isEqualTo("홍길동");
        assertThat(order.getShippingAddress().getRecipientPhone()).isEqualTo("010-1234-5678");
        assertThat(order.getShippingAddress().getZipCode()).isEqualTo("06236");
        assertThat(order.getShippingAddress().getAddress()).isEqualTo("서울특별시 강남구 테헤란로 123");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    private ShippingAddress shippingAddress() {
        return ShippingAddress.of("홍길동", "010-1234-5678", "06236", "서울특별시 강남구 테헤란로 123");
    }
}
