package com.server.zelo.domain.order.service;

import static com.server.zelo.support.Concurrency.UNIT_PRICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.repository.ChatRoomRepository;
import com.server.zelo.domain.delivery.repository.DeliveryRepository;
import com.server.zelo.domain.inventory.entity.Inventory;
import com.server.zelo.domain.inventory.repository.InventoryRepository;
import com.server.zelo.domain.order.dto.request.CreateOrderRequest;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.repository.OrderRepository;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.payment.repository.PaymentRepository;
import com.server.zelo.domain.payment.service.PaymentFacade;
import com.server.zelo.domain.portone.client.PortOneClient;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import com.server.zelo.domain.quote.repository.QuoteRepository;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.entity.UserAddress;
import com.server.zelo.domain.user.repository.UserAddressRepository;
import com.server.zelo.domain.user.repository.UserRepository;
import com.server.zelo.support.Concurrency;
import com.server.zelo.support.Concurrency.Result;
import com.server.zelo.support.Concurrency.SavedOrder;
import com.server.zelo.support.Concurrency.Stock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

// 주문·결제·만료가 같은 주문에 겹쳐도 돈과 재고가 틀어지지 않는지 고정한다
// 두 요청이 겹치는 상황은 정해진 순서 양쪽으로 한 번씩, 동시에 출발시켜 20번 확인한다 (동시 출발은 매번 이기는 쪽이 다를 수 있다)
@SpringBootTest
@ActiveProfiles("test")
@Import(Concurrency.class)
// 만료·대사 스케줄러가 테스트 주문을 따로 처리하면 결과가 섞여서 끈다
@TestPropertySource(properties = "zelo.scheduler.enabled=false")
class OrderConcurrencyTest {

    private static final int THREAD_COUNT = 10;
    private static final int REPEAT_COUNT = 20;

    @MockitoSpyBean
    private PortOneClient portOneClient;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private OrderService orderService;

    @Autowired
    private Concurrency fixture;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private QuoteRepository quoteRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private UserAddressRepository userAddressRepository;

    private SavedOrder savedOrder;

    @AfterEach
    void tearDown() {
        if (savedOrder != null) {
            fixture.delete(savedOrder);
        }
    }

    // 1. 주문하기를 연타하거나 재요청이 겹친 상황
    @Test
    void 같은_견적으로_동시에_주문해도_주문은_한_건이고_재고는_한_번만_선점한다() throws InterruptedException {
        // given
        Stock stock = fixture.saveStock(10);
        User user = userRepository.save(
                User.of("order-" + token() + "@test.dev", "test-password", "동시성 테스트", "01012345678"));
        UserAddress address = userAddressRepository.save(
                UserAddress.of(user, "집", "홍길동", "01012345678", "12345", "서울특별시 강남구 테헤란로 1", true));
        ChatRoom room = chatRoomRepository.save(ChatRoom.of(user.getId()));
        String quoteId = UUID.randomUUID().toString();
        String confirmationId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plus(QuoteRepository.QUOTE_TTL);
        QuoteItem item = new QuoteItem(stock.productId(), "TEST", "동시성 테스트 상품", "1개", UNIT_PRICE, 1, UNIT_PRICE);
        quoteRepository.save(new Quote(quoteId, user.getId(), room.getId(), "동시성 테스트", List.of(item), List.of(),
                UNIT_PRICE, now, expiresAt, Set.of(), List.of(), null, List.of()));
        orderService.createConfirmation(confirmationId, user.getId(), room.getId(), quoteId, expiresAt);
        Set<Long> orderIds = ConcurrentHashMap.newKeySet();
        AtomicInteger createdCount = new AtomicInteger();

        try {
            // when
            Result result = fixture.runConcurrently(THREAD_COUNT, () -> {
                var created = orderFacade.createOrder(user.getId(), new CreateOrderRequest(quoteId, confirmationId));
                orderIds.add(created.response().orderId());
                if (created.created()) {
                    createdCount.incrementAndGet();
                }
            });

            // then
            Inventory inventory = inventoryRepository.findById(stock.inventoryId()).orElseThrow();
            assertThat(orderIds).hasSize(1);
            assertThat(createdCount.get()).isEqualTo(1);
            assertThat(countOrders(quoteId)).isEqualTo(1);
            assertThat(countPayments(orderIds.iterator().next())).isEqualTo(1);
            assertThat(inventory.getStockQuantity()).isEqualTo(10);
            assertThat(inventory.getReservedQuantity()).isEqualTo(1);
            // 나중 요청은 기존 주문을 돌려받고, 사용자 락을 3초 안에 못 얻은 요청만 실패할 수 있다
            assertThat(result.errorCodes()).isSubsetOf(ErrorCode.TIMEOUT_EXCEPTION);
            assertThat(result.successCount() + result.failures().size()).isEqualTo(THREAD_COUNT);
        } finally {
            deleteOrders(quoteId);
            chatRoomRepository.deleteById(room.getId());
            userAddressRepository.deleteById(address.getId());
            userRepository.deleteById(user.getId());
            fixture.delete(stock);
        }
    }

    // 2. 결제창에서 결제를 마친 순간 결제 대기 시간이 끝난 상황
    @ParameterizedTest(name = "결제 확인 먼저: {0}")
    @ValueSource(booleans = {true, false})
    void 결제_확인과_주문_만료가_차례로_와도_결제와_주문이_맞는다(boolean confirmFirst) throws InterruptedException {
        boolean paid = confirmAndExpire(false, confirmFirst);

        assertThat(paid).isEqualTo(confirmFirst);
    }

    @RepeatedTest(REPEAT_COUNT)
    void 결제_확인과_주문_만료가_동시에_와도_결제와_주문이_맞는다() throws InterruptedException {
        confirmAndExpire(true, true);
    }

    // 3. 결제를 마친 순간 사용자가 주문 취소를 누른 상황
    @ParameterizedTest(name = "결제 확인 먼저: {0}")
    @ValueSource(booleans = {true, false})
    void 결제_전_취소와_결제_확인이_차례로_와도_결제와_주문이_맞는다(boolean confirmFirst) throws InterruptedException {
        boolean paid = cancelAndConfirm(false, confirmFirst);

        assertThat(paid).isEqualTo(confirmFirst);
    }

    @RepeatedTest(REPEAT_COUNT)
    void 결제_전_취소와_결제_확인이_동시에_와도_결제와_주문이_맞는다() throws InterruptedException {
        cancelAndConfirm(true, true);
    }

    // 4. 결제 대기 시간이 끝난 순간 사용자가 주문 취소를 누른 상황
    @ParameterizedTest(name = "취소 먼저: {0}")
    @ValueSource(booleans = {true, false})
    void 결제_전_취소와_주문_만료가_차례로_와도_한_번만_취소된다(boolean cancelFirst) throws InterruptedException {
        boolean canceledByUser = cancelAndExpire(false, cancelFirst);

        assertThat(canceledByUser).isEqualTo(cancelFirst);
    }

    @RepeatedTest(REPEAT_COUNT)
    void 결제_전_취소와_주문_만료가_동시에_와도_한_번만_취소된다() throws InterruptedException {
        cancelAndExpire(true, true);
    }

    // 5. 결제창을 열지 않은 결제를 정리하는 순간 주문이 만료된 상황
    @ParameterizedTest(name = "결제 정리 먼저: {0}")
    @ValueSource(booleans = {true, false})
    void 결제_대기_정리와_주문_만료가_차례로_와도_대기_주문의_결제를_실패시키지_않는다(boolean closeFirst)
            throws InterruptedException {
        PaymentStatus closed = closeReadyAndExpire(false, closeFirst);

        assertThat(closed).isEqualTo(closeFirst ? PaymentStatus.READY : PaymentStatus.FAILED);
    }

    @RepeatedTest(REPEAT_COUNT)
    void 결제_대기_정리와_주문_만료가_동시에_와도_대기_주문의_결제를_실패시키지_않는다() throws InterruptedException {
        closeReadyAndExpire(true, true);
    }

    // 결제됐으면 true, 주문이 먼저 닫혀 환불했으면 false
    private boolean confirmAndExpire(boolean together, boolean confirmFirst) throws InterruptedException {
        savedOrder = fixture.saveOrderWithReadyPayment();
        AtomicReference<Outcome<PaymentStatus>> confirm = new AtomicReference<>();
        AtomicReference<Outcome<Boolean>> expire = new AtomicReference<>();

        race(together, confirmFirst,
                () -> confirm.set(capture(() -> paymentFacade.confirmPayment(savedOrder.portoneId()))),
                () -> expire.set(capture(() -> orderFacade.cancelExpiredOrder(savedOrder.orderId(), cutoff()))));

        boolean paid = assertPaidOrRefunded();
        assertThat(confirm.get()).isEqualTo(Outcome.success(paid ? PaymentStatus.PAID : PaymentStatus.CANCELED));
        assertThat(expire.get()).isEqualTo(Outcome.success(!paid));
        return paid;
    }

    private boolean cancelAndConfirm(boolean together, boolean confirmFirst) throws InterruptedException {
        savedOrder = fixture.saveOrderWithReadyPayment();
        AtomicReference<Outcome<PaymentStatus>> confirm = new AtomicReference<>();
        AtomicReference<Outcome<Boolean>> cancel = new AtomicReference<>();

        race(together, confirmFirst,
                () -> confirm.set(capture(() -> paymentFacade.confirmPayment(savedOrder.portoneId()))),
                () -> cancel.set(capture(this::cancelBeforePayment)));

        boolean paid = assertPaidOrRefunded();
        assertThat(confirm.get()).isEqualTo(Outcome.success(paid ? PaymentStatus.PAID : PaymentStatus.CANCELED));
        assertThat(cancel.get()).isEqualTo(paid ? Outcome.failure(ErrorCode.INVALID_ORDER_STATUS) : Outcome.success(true));
        return paid;
    }

    // 사용자 취소가 이겼으면 true, 만료가 이겼으면 false
    private boolean cancelAndExpire(boolean together, boolean cancelFirst) throws InterruptedException {
        savedOrder = fixture.saveOrderWithReadyPayment();
        AtomicReference<Outcome<Boolean>> cancel = new AtomicReference<>();
        AtomicReference<Outcome<Boolean>> expire = new AtomicReference<>();

        race(together, cancelFirst,
                () -> cancel.set(capture(this::cancelBeforePayment)),
                () -> expire.set(capture(() -> orderFacade.cancelExpiredOrder(savedOrder.orderId(), cutoff()))));

        boolean canceledByUser = cancel.get().errorCode() == null;
        assertCanceledOnce();
        assertThat(findPaymentStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(cancel.get()).isEqualTo(
                canceledByUser ? Outcome.success(true) : Outcome.failure(ErrorCode.INVALID_ORDER_STATUS));
        assertThat(expire.get()).isEqualTo(Outcome.success(!canceledByUser));
        return canceledByUser;
    }

    // 정리가 끝난 결제 상태를 돌려준다. 주문이 대기 중일 때 정리했으면 READY, 만료 뒤에 정리했으면 FAILED
    private PaymentStatus closeReadyAndExpire(boolean together, boolean closeFirst) throws InterruptedException {
        savedOrder = fixture.saveOrderWithReadyPayment();
        // 결제창을 열지 않은 결제는 포트원에 기록이 없다
        willThrow(new BusinessException(ErrorCode.PAYMENT_NOT_FOUND))
                .given(portOneClient).getPayment(savedOrder.portoneId());
        AtomicReference<Outcome<PaymentStatus>> close = new AtomicReference<>();
        AtomicReference<Outcome<Boolean>> expire = new AtomicReference<>();

        race(together, closeFirst,
                () -> close.set(capture(() -> paymentFacade.closeReady(savedOrder.portoneId()))),
                () -> expire.set(capture(() -> orderFacade.cancelExpiredOrder(savedOrder.orderId(), cutoff()))));

        PaymentStatus closed = close.get().value();
        assertThat(expire.get()).isEqualTo(Outcome.success(true));
        assertCanceledOnce();
        assertThat(closed).isIn(PaymentStatus.READY, PaymentStatus.FAILED);
        assertThat(findPaymentStatus()).isEqualTo(closed);
        return closed;
    }

    // together면 두 요청을 같은 순간에 출발시키고, 아니면 firstFirst 순서대로 하나씩 실행한다
    private void race(boolean together, boolean firstFirst, Runnable first, Runnable second)
            throws InterruptedException {
        if (!together) {
            (firstFirst ? first : second).run();
            (firstFirst ? second : first).run();
            return;
        }
        AtomicInteger turn = new AtomicInteger();
        Result result = fixture.runConcurrently(2, () -> (turn.getAndIncrement() == 0 ? first : second).run());
        // 업무 예외는 각 요청이 Outcome으로 받았으므로 여기 남은 건 예상하지 못한 오류다
        assertThat(result.failures()).isEmpty();
    }

    // 결제됐으면 재고 차감·배송까지, 환불했으면 주문 취소·선점 해제·포트원 취소 1번까지 확인한다
    private boolean assertPaidOrRefunded() {
        Order order = orderRepository.findById(savedOrder.orderId()).orElseThrow();
        Inventory inventory = inventoryRepository.findById(savedOrder.stock().inventoryId()).orElseThrow();
        assertThat(inventory.getReservedQuantity()).isZero();
        if (order.getStatus() == OrderStatus.PAID) {
            assertThat(findPaymentStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(inventory.getStockQuantity()).isEqualTo(9);
            assertThat(countDeliveries()).isEqualTo(1);
            then(portOneClient).should(never()).cancelPayment(any(), any());
            return true;
        }
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(findPaymentStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(inventory.getStockQuantity()).isEqualTo(10);
        assertThat(countDeliveries()).isZero();
        then(portOneClient).should(times(1)).cancelPayment(eq(savedOrder.portoneId()), any());
        return false;
    }

    // 결제 전 주문이 취소됐고, 선점 해제와 취소 기록이 한 번씩만 일어났는지 확인한다
    private void assertCanceledOnce() {
        Order order = orderRepository.findById(savedOrder.orderId()).orElseThrow();
        Inventory inventory = inventoryRepository.findById(savedOrder.stock().inventoryId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(inventory.getStockQuantity()).isEqualTo(10);
        assertThat(inventory.getReservedQuantity()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from order_audit_logs where order_id = ? and action = 'CANCELED'",
                Integer.class, savedOrder.orderId())).isEqualTo(1);
    }

    private boolean cancelBeforePayment() {
        orderFacade.cancelBeforePayment(savedOrder.userId(), savedOrder.orderId(), "동시성 테스트 취소");
        return true;
    }

    // 주문이 막 만들어졌어도 만료 대상이 되도록 기준 시각을 미래로 둔다
    private LocalDateTime cutoff() {
        return LocalDateTime.now().plusMinutes(1);
    }

    private PaymentStatus findPaymentStatus() {
        return paymentRepository.findByPortoneId(savedOrder.portoneId()).orElseThrow().getStatus();
    }

    private long countDeliveries() {
        return deliveryRepository.findAll().stream()
                .filter(delivery -> delivery.getOrderId().equals(savedOrder.orderId()))
                .count();
    }

    private Integer countOrders(String quoteId) {
        return jdbcTemplate.queryForObject("select count(*) from orders where quote_id = ?", Integer.class, quoteId);
    }

    private Integer countPayments(Long orderId) {
        return jdbcTemplate.queryForObject("select count(*) from payments where order_id = ?", Integer.class, orderId);
    }

    // 주문 생성 테스트는 채팅방·배송지까지 만들어서 Concurrency.delete 대신 순서대로 직접 지운다
    private void deleteOrders(String quoteId) {
        String orderIds = "select id from orders where quote_id = ?";
        jdbcTemplate.update("delete from payments where order_id in (" + orderIds + ")", quoteId);
        jdbcTemplate.update("delete from order_audit_logs where quote_id = ?", quoteId);
        jdbcTemplate.update("delete from order_items where order_id in (" + orderIds + ")", quoteId);
        jdbcTemplate.update("delete from orders where quote_id = ?", quoteId);
    }

    private static <T> Outcome<T> capture(Supplier<T> call) {
        try {
            return Outcome.success(call.get());
        } catch (BusinessException e) {
            return Outcome.failure(e.getErrorCode());
        }
    }

    private static String token() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // 요청 하나의 결과. 성공하면 value, 업무 예외로 끝나면 errorCode
    private record Outcome<T>(T value, ErrorCode errorCode) {

        static <T> Outcome<T> success(T value) {
            return new Outcome<>(value, null);
        }

        static <T> Outcome<T> failure(ErrorCode errorCode) {
            return new Outcome<>(null, errorCode);
        }
    }
}
