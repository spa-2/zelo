package com.server.zelo.domain.order.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.service.ChatRoomService;
import com.server.zelo.domain.inventory.service.InventoryService;
import com.server.zelo.domain.order.dto.request.CreateOrderConfirmationRequest;
import com.server.zelo.domain.order.dto.response.CreateOrderConfirmationResponse;
import com.server.zelo.domain.order.repository.OrderConfirmationRepository;
import com.server.zelo.domain.order.repository.OrderRepository;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import com.server.zelo.domain.quote.repository.QuoteRepository;
import com.server.zelo.domain.quote.service.QuoteService;
import com.server.zelo.domain.user.dto.response.GetUserAddressResponse;
import com.server.zelo.domain.user.service.UserAddressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderConfirmationCommandServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long ROOM_ID = 10L;
    private static final String QUOTE_ID = "QUOTE-114";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2030, 1, 2, 12, 0);
    private static final LocalDateTime EXPIRES_AT = CREATED_AT.plusMinutes(10);
    private static final CreateOrderConfirmationRequest REQUEST = new CreateOrderConfirmationRequest(ROOM_ID, QUOTE_ID);

    @Mock
    private QuoteRepository quoteRepository;

    @Mock
    private ChatRoomService chatRoomService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private UserAddressService userAddressService;

    @Mock
    private OrderConfirmationRepository orderConfirmationRepository;

    @Captor
    private ArgumentCaptor<String> confirmationIdCaptor;

    private OrderCommandService orderCommandService;

    @BeforeEach
    void setUp() {
        // 실제 견적 검증부터 확인 정보 저장까지 이어지는 흐름을 확인한다.
        OrderService orderService = new OrderService(orderRepository, orderConfirmationRepository);
        QuoteService quoteService = new QuoteService(quoteRepository);
        orderCommandService = new OrderCommandService(
                orderService, quoteService, chatRoomService, inventoryService, userAddressService);
    }

    @Test
    void 소유권과_견적을_검증하고_기본_배송지로_확인_정보를_발급한다() {
        // given
        stubQuoteAndAddresses(List.of(
                address("회사", "부산광역시 해운대구 센텀로 1", false),
                address("집", "서울특별시 강남구 테헤란로 123", true)));

        // when
        CreateOrderConfirmationResponse result = orderCommandService.createConfirmation(USER_ID, REQUEST);

        // then
        assertThat(UUID.fromString(result.confirmationId()).toString()).isEqualTo(result.confirmationId());
        assertThat(result.roomId()).isEqualTo(ROOM_ID);
        assertThat(result.quoteId()).isEqualTo(QUOTE_ID);
        assertThat(result.totalAmount()).isEqualTo(10_500L);
        assertThat(result.addressSummary()).isEqualTo("집 · 서울특별시 강남구");
        assertThat(result.expiresAt()).isEqualTo(EXPIRES_AT);
        InOrder calls = inOrder(chatRoomService, quoteRepository, userAddressService, orderConfirmationRepository);
        calls.verify(chatRoomService).getOwnedChatRoom(ROOM_ID, USER_ID);
        calls.verify(quoteRepository).findByRoomId(ROOM_ID);
        calls.verify(userAddressService).getUserAddresses(USER_ID);
        calls.verify(orderConfirmationRepository).save(
                result.confirmationId(), USER_ID, ROOM_ID, QUOTE_ID, EXPIRES_AT);
        calls.verifyNoMoreInteractions();
        verifyNoInteractions(orderRepository, inventoryService);
    }

    @Test
    void 같은_견적으로_다시_발급해도_서로_다른_UUID를_사용한다() {
        // given
        stubQuoteAndAddresses(List.of(address("집", "서울특별시 강남구 테헤란로 123", true)));

        // when
        CreateOrderConfirmationResponse first = orderCommandService.createConfirmation(USER_ID, REQUEST);
        CreateOrderConfirmationResponse second = orderCommandService.createConfirmation(USER_ID, REQUEST);

        // then
        assertThat(first.confirmationId()).isNotEqualTo(second.confirmationId());
        then(orderConfirmationRepository).should(times(2)).save(
                confirmationIdCaptor.capture(), eq(USER_ID), eq(ROOM_ID), eq(QUOTE_ID), eq(EXPIRES_AT));
        assertThat(confirmationIdCaptor.getAllValues()).containsExactly(first.confirmationId(), second.confirmationId());
        assertThat(confirmationIdCaptor.getAllValues()).allSatisfy(id ->
                assertThat(UUID.fromString(id).toString()).isEqualTo(id));
        verifyNoInteractions(orderRepository, inventoryService);
    }

    @ParameterizedTest
    @MethodSource("addressesWithoutDefault")
    void 기본_배송지가_없으면_INVALID_INPUT이며_확인_정보를_저장하지_않는다(
            List<GetUserAddressResponse> addresses) {
        // given
        stubQuoteAndAddresses(addresses);

        // when
        // then
        assertThatThrownBy(() -> orderCommandService.createConfirmation(USER_ID, REQUEST))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        verifyNoInteractions(orderConfirmationRepository, orderRepository, inventoryService);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"CHAT_ROOM_NOT_FOUND", "CHAT_ROOM_ACCESS_DENIED"})
    void 채팅방_검증에_실패하면_견적과_배송지를_조회하거나_확인_정보를_저장하지_않는다(ErrorCode code) {
        // given
        BusinessException failure = new BusinessException(code);
        given(chatRoomService.getOwnedChatRoom(ROOM_ID, USER_ID)).willThrow(failure);

        // when
        // then
        assertThatThrownBy(() -> orderCommandService.createConfirmation(USER_ID, REQUEST)).isSameAs(failure);
        then(chatRoomService).should().getOwnedChatRoom(ROOM_ID, USER_ID);
        verifyNoInteractions(quoteRepository, userAddressService, orderConfirmationRepository, orderRepository, inventoryService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidQuotes")
    void 견적_검증에_실패하면_해당_예외를_반환하고_배송지_조회와_발급을_진행하지_않는다(
            String scenario, Optional<Quote> currentQuote, ErrorCode expectedCode) {
        // given
        given(chatRoomService.getOwnedChatRoom(ROOM_ID, USER_ID)).willReturn(ChatRoom.of(USER_ID));
        given(quoteRepository.findByRoomId(ROOM_ID)).willReturn(currentQuote);

        // when
        // then
        assertThatThrownBy(() -> orderCommandService.createConfirmation(USER_ID, REQUEST))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(expectedCode));
        InOrder calls = inOrder(chatRoomService, quoteRepository);
        calls.verify(chatRoomService).getOwnedChatRoom(ROOM_ID, USER_ID);
        calls.verify(quoteRepository).findByRoomId(ROOM_ID);
        calls.verifyNoMoreInteractions();
        verifyNoInteractions(userAddressService, orderConfirmationRepository, orderRepository, inventoryService);
    }

    @ParameterizedTest
    @CsvSource({
            "부산광역시 해운대구 센텀로 1, 집 · 부산광역시 해운대구",
            "세종특별자치시, 집 · 세종특별자치시"
    })
    void 주소_요약은_장소명과_앞의_지역명으로_구성한다(String address, String expectedSummary) {
        // given
        stubQuoteAndAddresses(List.of(address("집", address, true)));

        // when
        CreateOrderConfirmationResponse result = orderCommandService.createConfirmation(USER_ID, REQUEST);

        // then
        assertThat(result.addressSummary()).isEqualTo(expectedSummary);
    }

    @Test
    void 확인_정보_저장에_실패하면_성공_응답을_반환하지_않는다() {
        // given
        stubQuoteAndAddresses(List.of(address("집", "서울특별시 강남구 테헤란로 123", true)));
        RedisConnectionFailureException failure = new RedisConnectionFailureException("테스트 연결 실패");
        willThrow(failure).given(orderConfirmationRepository).save(
                anyString(), eq(USER_ID), eq(ROOM_ID), eq(QUOTE_ID), eq(EXPIRES_AT));

        // when
        // then
        assertThatThrownBy(() -> orderCommandService.createConfirmation(USER_ID, REQUEST)).isSameAs(failure);
        verifyNoInteractions(orderRepository, inventoryService);
    }

    private void stubQuoteAndAddresses(List<GetUserAddressResponse> addresses) {
        given(chatRoomService.getOwnedChatRoom(ROOM_ID, USER_ID)).willReturn(ChatRoom.of(USER_ID));
        given(quoteRepository.findByRoomId(ROOM_ID)).willReturn(Optional.of(quote(USER_ID, QUOTE_ID)));
        given(userAddressService.getUserAddresses(USER_ID)).willReturn(addresses);
    }

    private static Quote quote(Long userId, String quoteId) {
        return new Quote(
                quoteId, userId, ROOM_ID, "김치볶음밥 재료",
                List.of(
                        new QuoteItem(1L, "P-001", "신선 달걀", "10구", 4_500L, 1, 4_500L),
                        new QuoteItem(2L, "P-002", "볶음김치", "500g", 6_000L, 1, 6_000L)),
                List.of(), 10_500L, CREATED_AT, EXPIRES_AT);
    }

    private static Stream<Arguments> invalidQuotes() {
        return Stream.of(
                Arguments.of("견적이 없거나 Redis에서 만료됨", Optional.<Quote>empty(), ErrorCode.QUOTE_EXPIRED),
                Arguments.of("타인 견적", Optional.of(quote(999L, QUOTE_ID)), ErrorCode.NO_AUTHORITY),
                Arguments.of("현재 견적 ID와 불일치", Optional.of(quote(USER_ID, "QUOTE-NEW")), ErrorCode.QUOTE_NOT_CURRENT),
                Arguments.of("소유권과 견적 ID가 모두 불일치", Optional.of(quote(999L, "QUOTE-NEW")), ErrorCode.NO_AUTHORITY)
        );
    }

    private static Stream<List<GetUserAddressResponse>> addressesWithoutDefault() {
        return Stream.of(List.<GetUserAddressResponse>of(),
                List.of(address("회사", "서울특별시 강남구 테헤란로 123", false)));
    }

    private static GetUserAddressResponse address(String place, String address, boolean isDefault) {
        return new GetUserAddressResponse(1L, place, "테스트 사용자", "01012345678", "12345", address, isDefault);
    }
}
