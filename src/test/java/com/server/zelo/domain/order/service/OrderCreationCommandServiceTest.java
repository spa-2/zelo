package com.server.zelo.domain.order.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.service.ChatRoomService;
import com.server.zelo.domain.inventory.service.InventoryService;
import com.server.zelo.domain.order.dto.request.CreateOrderRequest;
import com.server.zelo.domain.order.entity.ActorType;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderItem;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.entity.ShippingAddress;
import com.server.zelo.domain.order.entity.StockState;
import com.server.zelo.domain.order.model.CreateOrderResult;
import com.server.zelo.domain.order.model.OrderConfirmation;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import com.server.zelo.domain.quote.service.QuoteService;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.entity.UserAddress;
import com.server.zelo.domain.user.service.UserAddressService;
import com.server.zelo.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderCreationCommandServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long ROOM_ID = 10L;
    private static final String QUOTE_ID = "QUOTE-161";
    private static final String CONFIRMATION_ID = "confirmation-161";
    private static final CreateOrderRequest REQUEST = new CreateOrderRequest(QUOTE_ID, CONFIRMATION_ID);
    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 2, 12, 0);

    @Mock private UserService userService;
    @Mock private OrderService orderService;
    @Mock private QuoteService quoteService;
    @Mock private ProductService productService;
    @Mock private ChatRoomService chatRoomService;
    @Mock private InventoryService inventoryService;
    @Mock private UserAddressService userAddressService;
    @Mock private User user;
    @Mock private ChatRoom chatRoom;

    private OrderTestTransactionManager transactionManager;
    private OrderCommandService command;

    @BeforeEach
    void setUp() {
        transactionManager = new OrderTestTransactionManager();
        command = new OrderCommandService(userService, orderService, quoteService, productService,
                chatRoomService, inventoryService, userAddressService,
                new TransactionTemplate(transactionManager));
    }

    @Test
    void 견적과_배송지를_복사하고_상품_ID_순서로_선점해_신규_주문을_반환한다() {
        // given
        stubStart(quote());
        stubProducts();
        UserAddress address = stubAddress();
        stubSave();

        // when
        CreateOrderResult result = command.createOrder(USER_ID, REQUEST);
        address.update("회사", "변경 이름", "01099999999", "99999", "변경 주소");

        // then
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        then(orderService).should().createOrder(captor.capture());
        Order saved = captor.getValue();
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getChatRoom()).isSameAs(chatRoom);
        assertThat(saved.getQuoteId()).isEqualTo(QUOTE_ID);
        assertThat(saved.getOrderName()).isEqualTo("사과와 배 재료");
        assertThat(saved.getRequesterType()).isEqualTo(ActorType.USER);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(saved.getTotalAmount()).isEqualTo(7_000L);
        assertThat(saved.getShippingAddress().getRecipientName()).isEqualTo("홍길동");
        assertThat(saved.getShippingAddress().getRecipientPhone()).isEqualTo("01012345678");
        assertThat(saved.getShippingAddress().getZipCode()).isEqualTo("12345");
        assertThat(saved.getShippingAddress().getAddress()).isEqualTo("서울특별시 강남구 테헤란로 1");
        assertThat(saved.getOrderItems()).extracting(OrderItem::getOrder).containsOnly(saved);
        assertThat(saved.getOrderItems()).extracting(OrderItem::getStockState).containsOnly(StockState.RESERVED);
        assertThat(result.created()).isTrue();
        assertThat(result.response().orderId()).isEqualTo(101L);
        assertThat(result.response().chatRoomId()).isEqualTo(ROOM_ID);
        assertThat(result.response().createdAt()).isEqualTo(NOW);
        assertThat(result.response().items()).extracting(item -> item.productName())
                .containsExactly("견적 배", "견적 사과");
        assertThat(result.response().items()).extracting(item -> item.productUnit())
                .containsExactly("2개입", "1개");
        assertThat(result.response().items()).extracting(item -> item.quantity()).containsExactly(2, 3);
        assertThat(result.response().items()).extracting(item -> item.lineAmount()).containsExactly(4_000L, 3_000L);
        then(productService).should().findProducts(List.of(20L, 10L));
        InOrder calls = inOrder(orderService, inventoryService);
        calls.verify(orderService).createOrder(any(Order.class));
        calls.verify(inventoryService).reserve(10L, 3);
        calls.verify(inventoryService).reserve(20L, 2);
        calls.verify(orderService).deleteConfirmationAfterCommit(CONFIRMATION_ID);
        assertThat(transactionManager.events).containsExactly("begin", "commit");
    }

    @Test
    void 이미_생성된_주문은_확인정보와_재고를_재처리하지_않고_반환한다() {
        // given
        Order existing = existingOrder();
        given(orderService.findOwnedOrderByQuoteId(USER_ID, QUOTE_ID)).willReturn(Optional.of(existing));

        // when
        CreateOrderResult result = command.createOrder(USER_ID, REQUEST);

        // then
        assertThat(result.created()).isFalse();
        assertThat(result.response().orderId()).isEqualTo(101L);
        then(orderService).should(never()).validateConfirmation(any(), any(), any());
        then(orderService).should(never()).validateNoPendingOrder(any());
        then(orderService).should(never()).createOrder(any());
        then(orderService).should(never()).deleteConfirmationAfterCommit(any());
        verifyNoInteractions(quoteService, chatRoomService, productService, userService,
                userAddressService, inventoryService);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"INVALID_INPUT", "NO_AUTHORITY"})
    void 확인정보_검증에_실패하면_후속_처리를_하지_않는다(ErrorCode code) {
        // given
        given(orderService.findOwnedOrderByQuoteId(USER_ID, QUOTE_ID)).willReturn(Optional.empty());
        given(orderService.validateConfirmation(USER_ID, QUOTE_ID, CONFIRMATION_ID))
                .willThrow(new BusinessException(code));

        // when
        // then
        assertError(code);
        verifyNoInteractions(chatRoomService, quoteService, productService, inventoryService, userAddressService);
        assertThat(transactionManager.events).containsExactly("begin", "rollback");
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"QUOTE_EXPIRED", "QUOTE_NOT_CURRENT"})
    void 견적_재검증에_실패하면_주문과_재고를_저장하지_않는다(ErrorCode code) {
        // given
        stubConfirmation();
        given(chatRoomService.getOwnedChatRoom(ROOM_ID, USER_ID)).willReturn(chatRoom);
        given(quoteService.validateForOrder(ROOM_ID, USER_ID, QUOTE_ID)).willThrow(new BusinessException(code));

        // when
        // then
        assertError(code);
        verifyNoInteractions(productService, inventoryService, userAddressService);
        then(orderService).should(never()).createOrder(any());
    }

    @Test
    void 채팅방_소유권이_없으면_견적_조회_전에_거부한다() {
        // given
        stubConfirmation();
        given(chatRoomService.getOwnedChatRoom(ROOM_ID, USER_ID))
                .willThrow(new BusinessException(ErrorCode.NO_AUTHORITY));

        // when
        // then
        assertError(ErrorCode.NO_AUTHORITY);
        verifyNoInteractions(quoteService, productService, inventoryService);
    }

    @Test
    void 결제_대기_주문이_있으면_상품_조회_전에_거부한다() {
        // given
        stubStart(quote());
        willThrow(new BusinessException(ErrorCode.PENDING_ORDER_EXISTS))
                .given(orderService).validateNoPendingOrder(USER_ID);

        // when
        // then
        assertError(ErrorCode.PENDING_ORDER_EXISTS);
        verifyNoInteractions(productService, userAddressService, inventoryService);
    }

    @ParameterizedTest
    @MethodSource("invalidQuotes")
    void 품목이나_금액이_잘못된_견적은_상품_조회_전에_거부한다(Quote quote, ErrorCode code) {
        // given
        stubStart(quote);

        // when
        // then
        assertError(code);
        verifyNoInteractions(productService, inventoryService);
        then(orderService).should(never()).createOrder(any());
    }

    static Stream<Arguments> invalidQuotes() {
        return Stream.of(
                Arguments.of(quote(null, 1L), ErrorCode.INVALID_INPUT),
                Arguments.of(quote(List.of(), 1L), ErrorCode.INVALID_INPUT),
                Arguments.of(quote(List.of(item(10L, 1_000L, 1, 1_000L)), null), ErrorCode.INVALID_INPUT),
                Arguments.of(quote(List.of(item(10L, 1_000L, 1, 1_000L)), -1L), ErrorCode.INVALID_INPUT),
                Arguments.of(quote(Arrays.asList((QuoteItem) null), 1L), ErrorCode.INVALID_INPUT),
                Arguments.of(quote(List.of(item(null, 1L, 1, 1L)), 1L), ErrorCode.INVALID_INPUT),
                Arguments.of(quote(List.of(item(10L, null, 1, 1L)), 1L), ErrorCode.INVALID_INPUT),
                Arguments.of(quote(List.of(item(10L, -1L, 1, 1L)), 1L), ErrorCode.INVALID_INPUT),
                Arguments.of(quote(List.of(item(10L, 1L, 1, null)), 1L), ErrorCode.INVALID_INPUT),
                Arguments.of(quote(List.of(item(10L, 1L, 1, -1L)), 1L), ErrorCode.INVALID_INPUT),
                Arguments.of(quote(List.of(item(10L, 1L, null, 1L)), 1L), ErrorCode.INVALID_QUANTITY),
                Arguments.of(quote(List.of(item(10L, 1L, 0, 0L)), 0L), ErrorCode.INVALID_QUANTITY),
                Arguments.of(quote(List.of(item(10L, 1L, -1, 1L)), 1L), ErrorCode.INVALID_QUANTITY)
        );
    }

    @Test
    void 견적_상품이_없으면_주문_생성을_거부한다() {
        // given
        stubStart(quote());
        given(productService.findProducts(anyList())).willReturn(List.of());

        // when
        // then
        assertError(ErrorCode.PRODUCT_NOT_FOUND);
        then(orderService).should(never()).createOrder(any());
        verifyNoInteractions(inventoryService);
    }

    @Test
    void 현재_상품_가격이_견적_단가와_다르면_거부한다() {
        // given
        stubStart(quote(List.of(item(10L, 1_000L, 1, 1_000L)), 1_000L));
        stubSingleProduct(2_000L);

        // when
        // then
        assertError(ErrorCode.PRICE_CHANGED);
        then(orderService).should(never()).createOrder(any());
        verifyNoInteractions(inventoryService);
    }

    @ParameterizedTest
    @MethodSource("invalidCalculatedAmounts")
    void 품목금액_합계_불일치와_Long_범위_초과를_거부한다(Quote quote, Long currentPrice) {
        // given
        stubStart(quote);
        stubSingleProduct(currentPrice);

        // when
        // then
        assertError(ErrorCode.INVALID_INPUT);
        then(orderService).should(never()).createOrder(any());
        verifyNoInteractions(inventoryService);
    }

    static Stream<Arguments> invalidCalculatedAmounts() {
        return Stream.of(
                Arguments.of(quote(List.of(item(10L, 1_000L, 2, 1_000L)), 1_000L), 1_000L),
                Arguments.of(quote(List.of(item(10L, 1_000L, 1, 1_000L)), 2_000L), 1_000L),
                Arguments.of(quote(List.of(item(10L, Long.MAX_VALUE, 2, 1L)), 1L), Long.MAX_VALUE),
                Arguments.of(quote(List.of(item(10L, Long.MAX_VALUE, 1, Long.MAX_VALUE),
                        item(10L, Long.MAX_VALUE, 1, Long.MAX_VALUE)), Long.MAX_VALUE), Long.MAX_VALUE)
        );
    }

    @Test
    void 기본_배송지가_없으면_INVALID_INPUT으로_거부한다() {
        // given
        stubStart(quote());
        stubProducts();
        given(userService.getUser(USER_ID)).willReturn(user);
        given(userAddressService.findDefaultAddress(USER_ID)).willReturn(Optional.empty());

        // when
        // then
        assertError(ErrorCode.INVALID_INPUT);
        then(orderService).should(never()).createOrder(any());
        verifyNoInteractions(inventoryService);
    }

    @Test
    void 두번째_품목_선점에_실패하면_트랜잭션을_롤백하고_확인정보를_삭제하지_않는다() {
        // given
        stubStart(quote());
        stubProducts();
        stubAddress();
        given(orderService.createOrder(any())).willAnswer(invocation -> invocation.getArgument(0));
        willDoNothing().given(inventoryService).reserve(10L, 3);
        willThrow(new BusinessException(ErrorCode.INSUFFICIENT_STOCK))
                .given(inventoryService).reserve(20L, 2);

        // when
        // then
        assertError(ErrorCode.INSUFFICIENT_STOCK);
        InOrder calls = inOrder(inventoryService);
        calls.verify(inventoryService).reserve(10L, 3);
        calls.verify(inventoryService).reserve(20L, 2);
        assertThat(transactionManager.events).containsExactly("begin", "rollback");
        then(orderService).should(never()).deleteConfirmationAfterCommit(any());
    }

    @Test
    void 유니크_충돌은_롤백_후_새_트랜잭션에서_본인_기존_주문을_반환한다() {
        // given
        stubBeforeSave();
        Order existing = existingOrder();
        DataIntegrityViolationException failure = uniqueFailure("23505");
        given(orderService.createOrder(any())).willThrow(failure);
        given(orderService.findOwnedOrderByQuoteId(USER_ID, QUOTE_ID))
                .willReturn(Optional.empty()).willAnswer(invocation -> {
                    assertThat(transactionManager.events).containsExactly("begin", "rollback", "begin");
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    return Optional.of(existing);
                });

        // when
        CreateOrderResult result = command.createOrder(USER_ID, REQUEST);

        // then
        assertThat(result.created()).isFalse();
        assertThat(result.response().orderId()).isEqualTo(101L);
        assertThat(transactionManager.events).containsExactly("begin", "rollback", "begin", "commit");
        verifyNoInteractions(inventoryService);
        then(orderService).should(never()).deleteConfirmationAfterCommit(any());
    }

    @ParameterizedTest
    @MethodSource("nonUniqueFailures")
    void 유니크_위반이_아닌_저장_오류는_기존_주문_조회로_덮지_않는다(DataIntegrityViolationException failure) {
        // given
        stubBeforeSave();
        given(orderService.createOrder(any())).willThrow(failure);

        // when
        // then
        assertThatThrownBy(() -> command.createOrder(USER_ID, REQUEST)).isSameAs(failure);
        then(orderService).should().findOwnedOrderByQuoteId(USER_ID, QUOTE_ID);
        assertThat(transactionManager.events).containsExactly("begin", "rollback");
        verifyNoInteractions(inventoryService);
    }

    static Stream<Arguments> nonUniqueFailures() {
        return Stream.of(Arguments.of(uniqueFailure("23502")),
                Arguments.of(new DataIntegrityViolationException("원인 없는 저장 오류")));
    }

    @Test
    void 유니크_충돌_후_같은_견적_주문이_없으면_원래_예외를_유지한다() {
        // given
        stubBeforeSave();
        DataIntegrityViolationException failure = uniqueFailure("23505");
        given(orderService.createOrder(any())).willThrow(failure);

        // when
        // then
        assertThatThrownBy(() -> command.createOrder(USER_ID, REQUEST)).isSameAs(failure);
        then(orderService).should(times(2)).findOwnedOrderByQuoteId(USER_ID, QUOTE_ID);
        assertThat(transactionManager.events).containsExactly("begin", "rollback", "begin", "rollback");
    }

    @Test
    void 충돌_복구에서도_다른_사용자의_주문을_반환하지_않는다() {
        // given
        stubBeforeSave();
        given(orderService.createOrder(any())).willThrow(uniqueFailure("23505"));
        given(orderService.findOwnedOrderByQuoteId(USER_ID, QUOTE_ID))
                .willReturn(Optional.empty()).willThrow(new BusinessException(ErrorCode.NO_AUTHORITY));

        // when
        // then
        assertError(ErrorCode.NO_AUTHORITY);
        assertThat(transactionManager.events).containsExactly("begin", "rollback", "begin", "rollback");
        verifyNoInteractions(inventoryService);
    }

    private void stubBeforeSave() {
        stubStart(quote());
        stubProducts();
        stubAddress();
    }

    private void stubConfirmation() {
        given(orderService.findOwnedOrderByQuoteId(USER_ID, QUOTE_ID)).willReturn(Optional.empty());
        given(orderService.validateConfirmation(USER_ID, QUOTE_ID, CONFIRMATION_ID))
                .willReturn(new OrderConfirmation(USER_ID, ROOM_ID, QUOTE_ID));
    }

    private void stubStart(Quote quote) {
        stubConfirmation();
        given(chatRoomService.getOwnedChatRoom(ROOM_ID, USER_ID)).willReturn(chatRoom);
        given(quoteService.validateForOrder(ROOM_ID, USER_ID, QUOTE_ID)).willReturn(quote);
    }

    private void stubProducts() {
        Product first = product(10L, 1_000L);
        Product second = product(20L, 2_000L);
        given(productService.findProducts(anyList())).willReturn(List.of(first, second));
    }

    private void stubSingleProduct(Long price) {
        Product product = product(10L, price);
        given(productService.findProducts(anyList())).willReturn(List.of(product));
    }

    private Product product(Long id, Long price) {
        Product product = mock(Product.class);
        given(product.getId()).willReturn(id);
        given(product.getPrice()).willReturn(price);
        return product;
    }

    private UserAddress stubAddress() {
        given(userService.getUser(USER_ID)).willReturn(user);
        UserAddress address = UserAddress.of(user, "집", "홍길동", "01012345678",
                "12345", "서울특별시 강남구 테헤란로 1", true);
        given(userAddressService.findDefaultAddress(USER_ID)).willReturn(Optional.of(address));
        return address;
    }

    private void stubSave() {
        given(chatRoom.getId()).willReturn(ROOM_ID);
        given(orderService.createOrder(any())).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 101L);
            ReflectionTestUtils.setField(order, "createdAt", NOW);
            return order;
        });
    }

    private Order existingOrder() {
        given(chatRoom.getId()).willReturn(ROOM_ID);
        Order order = Order.of(user, QUOTE_ID, "기존 주문", ActorType.USER, chatRoom, 0L,
                ShippingAddress.of("홍길동", "01012345678", "12345", "서울 주소"));
        ReflectionTestUtils.setField(order, "id", 101L);
        return order;
    }

    private void assertError(ErrorCode expected) {
        assertThatThrownBy(() -> command.createOrder(USER_ID, REQUEST))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private static DataIntegrityViolationException uniqueFailure(String sqlState) {
        return new DataIntegrityViolationException("저장 실패",
                new IllegalStateException(new SQLException("테스트 제약 위반", sqlState)));
    }

    private static Quote quote() {
        return quote(List.of(new QuoteItem(20L, "PEAR", "견적 배", "2개입", 2_000L, 2, 4_000L),
                new QuoteItem(10L, "APPLE", "견적 사과", "1개", 1_000L, 3, 3_000L)), 7_000L);
    }

    private static Quote quote(List<QuoteItem> items, Long total) {
        return new Quote(QUOTE_ID, USER_ID, ROOM_ID, "사과와 배 재료", items, List.of(), total, NOW, NOW.plusMinutes(10));
    }

    private static QuoteItem item(Long id, Long price, Integer quantity, Long lineAmount) {
        return new QuoteItem(id, "APPLE", "견적 사과", "1개", price, quantity, lineAmount);
    }
}
