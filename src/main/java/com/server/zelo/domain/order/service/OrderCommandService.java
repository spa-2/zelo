package com.server.zelo.domain.order.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.service.ChatRoomService;
import com.server.zelo.domain.delivery.entity.Delivery;
import com.server.zelo.domain.delivery.service.DeliveryService;
import com.server.zelo.domain.inventory.service.InventoryService;
import com.server.zelo.domain.order.dto.request.CreateOrderConfirmationRequest;
import com.server.zelo.domain.order.dto.request.CreateOrderRequest;
import com.server.zelo.domain.order.dto.response.CancelOrderResponse;
import com.server.zelo.domain.order.dto.response.CreateOrderConfirmationResponse;
import com.server.zelo.domain.order.dto.response.CreateOrderResponse;
import com.server.zelo.domain.order.dto.response.GetOrderDetailResponse;
import com.server.zelo.domain.order.entity.ActorType;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderItem;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.entity.ShippingAddress;
import com.server.zelo.domain.order.model.CreateOrderResult;
import com.server.zelo.domain.order.model.OrderConfirmation;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.payment.service.PaymentService;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import com.server.zelo.domain.quote.service.QuoteService;
import com.server.zelo.domain.refund.entity.Refund;
import com.server.zelo.domain.refund.service.RefundService;
import com.server.zelo.domain.user.dto.response.GetUserAddressResponse;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.entity.UserAddress;
import com.server.zelo.domain.user.service.UserAddressService;
import com.server.zelo.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class OrderCommandService {
    private final UserService userService;
    private final OrderService orderService;
    private final QuoteService quoteService;
    private final RefundService refundService;
    private final ProductService productService;
    private final PaymentService paymentService;
    private final ChatRoomService chatRoomService;
    private final DeliveryService deliveryService;
    private final InventoryService inventoryService;
    private final UserAddressService userAddressService;
    private final TransactionTemplate transactionTemplate;

    // 결제 성공시
    public void completePayment(Long orderId) {
        Order order = orderService.getOrderWithItemsForUpdate(orderId);
        order.pay(); // OrderStatus: PAYMENT_PENDING -> PAID

        // 품목별 재고 차감 확정
        for(OrderItem orderItem : getItemsInProductOrder(order)) {
            orderItem.deduct(); // StockState: RESERVED → DEDUCTED
            inventoryService.deduct(orderItem.getProduct().getId(), orderItem.getQuantity());
        }
    }

    // 결제 실패시
    public void failPayment(Long orderId) {
        Order order = orderService.getOrderWithItemsForUpdate(orderId);
        order.failPayment(); // OrderStatus: PAYMENT_PENDING -> PAYMENT_FAILED

        // 품목별 선점 해제
        for(OrderItem orderItem : getItemsInProductOrder(order)) {
            orderItem.release();  // StockState: RESERVED → RELEASED
            inventoryService.release(orderItem.getProduct().getId(), orderItem.getQuantity());
        }
    }

    /**
     * 주문 전체를 취소하고, 품목별 재고 처리 상태에 따라 재고를 되돌린다.
     *
     * <p>PAYMENT_PENDING 또는 PAID 주문을 CANCELED로 변경하고,
     * 각 품목의 재고 상태에 따라 다음과 같이 처리한다.
     * <ul>
     *   <li>RESERVED: 구매 수량만큼 선점을 해제한다.</li>
     *   <li>DEDUCTED: 이미 취소된 수량을 제외한 남은 수량만 복원한다.</li>
     *   <li>RESTORED: 이미 전량 복원된 품목이므로 건너뛴다.</li>
     *   <li>RELEASED: 허용되지 않은 상태로 판단하여 예외를 발생시킨다.</li>
     * </ul>
     *
     * <p>주문·품목 상태 변경과 재고 처리는 하나의 트랜잭션으로 수행하며,
     * 처리 중 예외가 발생하면 전체 변경을 롤백한다.
     *
     * @param orderId 취소할 주문 ID
     * @throws BusinessException 주문이 없으면 ORDER_NOT_FOUND,
     *         주문이 PAYMENT_PENDING·PAID가 아니면 INVALID_ORDER_STATUS,
     *         품목의 재고 상태가 허용되지 않으면 INVALID_STOCK_STATUS
     */
    public void cancel(Long orderId) {
        Order order = orderService.getOrderWithItemsForUpdate(orderId);
        order.cancel();

        for (OrderItem orderItem : getItemsInProductOrder(order)) {
            switch (orderItem.getStockState()) {
                case RESERVED -> {
                    // 결제 전에는 실재고를 복원하지 않고 선점만 해제
                    orderItem.release();
                    inventoryService.release(orderItem.getProduct().getId(), orderItem.getQuantity());
                }

                case DEDUCTED -> {
                    // restore() 후에는 남은 수량이 0이 되므로 먼저 보관
                    Integer remainingQuantity = orderItem.getCancelableQuantity();

                    orderItem.restore();
                    inventoryService.restore(orderItem.getProduct().getId(), remainingQuantity);
                }

                case RESTORED -> {
                    // 부분 환불 과정에서 이미 전량 복원된 품목은 건너뜀
                }

                case RELEASED -> throw new BusinessException(ErrorCode.INVALID_STOCK_STATUS);
            }
        }
    }

    public CreateOrderConfirmationResponse createConfirmation(Long userId, CreateOrderConfirmationRequest request) {
        // 본인 명의 채팅방 소유 확인
        chatRoomService.getOwnedChatRoom(request.roomId(), userId);

        // 견적 조회·검증 (Mock)
        Quote quote = quoteService.validateForOrder(request.roomId(), userId, request.quoteId());

        // 기본 배송지 조회
        GetUserAddressResponse defaultAddress = userAddressService.getUserAddresses(userId).stream()
                .filter(GetUserAddressResponse::isDefault)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, "기본 배송지가 없습니다."));

        String addrSummary = toAddressSummary(defaultAddress);

        // 확인 정보 발급
        String confirmationId = UUID.randomUUID().toString(); // 키 생성
        LocalDateTime expiresAt = quote.expiresAt(); // 견적에 있는 만료시각

        // Redis에 사용자 ID·roomId·quoteId 저장
        orderService.createConfirmation(confirmationId, userId, request.roomId(), request.quoteId(), expiresAt);

        return CreateOrderConfirmationResponse.of(confirmationId, quote, addrSummary, expiresAt);
    }

    // 주문 잠금 후 PAYMENT_FAILED로 바꿀 수 있을 때만 결제 실패 처리, 아니면 건너뜀
    public void failPaymentIfPossible(Long orderId) {
        Order order = orderService.getOrderWithItemsForUpdate(orderId);
        if (order.getStatus().isTransitionAllowed(OrderStatus.PAYMENT_FAILED)) {
            failPayment(orderId);
        } else {
            log.info("결제 실패 반영 건너뜀 orderId={}, status={}", orderId, order.getStatus());
        }
    }

    // 주문 잠금 후 CANCELED로 바꿀 수 있을 때만 취소 처리, 아니면 건너뜀
    public void cancelIfPossible(Long orderId) {
        Order order = orderService.getOrderWithItemsForUpdate(orderId);
        if (order.getStatus().isTransitionAllowed(OrderStatus.CANCELED)) {
            cancel(orderId);
        } else {
            log.info("취소 반영 건너뜀 orderId={}, status={}", orderId, order.getStatus());
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CreateOrderResult createOrder(Long userId, CreateOrderRequest request) {
        try {
            // 주문 저장과 모든 품목의 재고 선점을 하나의 트랜잭션으로 처리
            return Objects.requireNonNull(
                    transactionTemplate.execute(
                            status -> createOrderInTransaction(userId, request)
                    )
            );
        } catch (DataIntegrityViolationException exception) {
            // 유니크 위반 이외의 저장 오류는 기존 주문 재요청으로 처리하지 않음
            if (!isUniqueViolation(exception)) {
                throw exception;
            }

            // 실패한 생성 트랜잭션이 롤백된 뒤 새 트랜잭션에서 기존 주문을 조회함
            return Objects.requireNonNull(
                    transactionTemplate.execute(status -> {
                        Order existingOrder = orderService
                                .findOwnedOrderByQuoteId(userId, request.quoteId())
                                .orElseThrow(() -> exception);

                        // 지연 로딩이 필요한 응답 변환까지 트랜잭션 안에서 완료
                        return toExistingOrderResult(existingOrder);
                    })
            );
        }
    }

    /**
     * 본인의 결제 전 주문을 취소하고 선점 재고를 해제한다.
     * 결제 확인과의 동시 실행을 막는 Facade의 공통 락 안에서 호출한다.
     */
    public CancelOrderResponse cancelBeforePayment(Long userId, Long orderId) {
        // 상태 검증부터 재고 해제까지 주문 행 잠금을 유지
        Order order = orderService.getOrderWithItemsForUpdate(orderId);

        // 없는 주문은 위 조회에서 404, 다른 사용자의 주문은 여기서 403으로 구분
        if (!userId.equals(order.getUser().getId())) {
            throw new BusinessException(ErrorCode.ORDER_ACCESS_DENIED);
        }

        // 기존 cancel()은 PAID도 허용하므로 사용자 취소에서는 결제 전 상태로 제한
        if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }

        paymentService.findLatestPayment(orderId).ifPresent(payment -> {
            // 결제 확인 중이거나 이미 결제됐다면 사용자 취소로 선점 재고를 풀지 않음
            if (payment.getStatus() == PaymentStatus.CONFIRM_PENDING
                    || payment.getStatus() == PaymentStatus.PAID) {
                throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
            }
        });

        // 기존 주문 취소와 품목별 재고 처리 로직을 재사용
        cancel(orderId);

        return CancelOrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public Optional<String> findPaymentIdForCancellation(Long userId, Long orderId) {
        // Redis 락을 기다리는 동안 DB 행 잠금을 잡지 않도록 일반 조회
        Order order = orderService.getOrderWithItems(orderId);

        if (!userId.equals(order.getUser().getId())) {
            throw new BusinessException(ErrorCode.ORDER_ACCESS_DENIED);
        }

        if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }

        return paymentService.findLatestPayment(orderId)
                .map(payment -> {
                    // 확인 대기인 결제는 락을 기다리기 전에 취소 불가로 응답
                    if (payment.getStatus() == PaymentStatus.CONFIRM_PENDING
                            || payment.getStatus() == PaymentStatus.PAID) {
                        throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
                    }

                    return payment.getPortoneId();
                });
    }

    /**
     * 환불 완료된 주문 상품의 수량만큼 취소 이력을 반영하고 재고를 복원한다.
     *
     * <p>모든 요청 품목을 먼저 검증하고, 상품 ID 오름차순으로 재고를 복원한다.
     * 일부 수량만 취소되면 주문은 PAID를 유지하고,
     * 모든 품목이 전량 취소되면 CANCELED로 변경한다.
     *
     * @param orderId 대상 주문 ID
     * @param cancelQuantities 주문 상품 ID별 이번 취소 수량
     * @throws BusinessException 주문이 없으면 ORDER_NOT_FOUND,
     *         요청이 비어 있거나 해당 주문의 품목이 아니면 INVALID_INPUT,
     *         주문이 PAID가 아니면 INVALID_ORDER_STATUS,
     *         품목이 DEDUCTED가 아니면 INVALID_STOCK_STATUS,
     *         취소 수량이 올바르지 않으면 INVALID_REFUND_QUANTITY
     */
    public void cancelItems(Long orderId, Map<Long, Integer> cancelQuantities) {

        if (cancelQuantities == null || cancelQuantities.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // 같은 주문의 취소 처리가 동시에 수량을 변경하지 못하도록 잠금
        Order order = orderService.getOrderWithItemsForUpdate(orderId);

        if (order.getStatus() != OrderStatus.PAID) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }

        Map<Long, OrderItem> itemsById = order.getOrderItems().stream()
                .collect(Collectors.toMap(OrderItem::getId, item -> item));

        // 일부 품목을 변경한 뒤 다른 품목의 입력 오류가 발견되지 않도록 먼저 검증
        for (Map.Entry<Long, Integer> entry : cancelQuantities.entrySet()) {
            Long orderItemId = entry.getKey();

            if (orderItemId == null || orderItemId < 1) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }

            OrderItem orderItem = itemsById.get(orderItemId);

            if (orderItem == null) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "해당 주문에 속한 주문 상품만 취소할 수 있습니다.");
            }

            orderItem.validateCancelQuantity(entry.getValue());
        }

        // 다른 재고 변경 작업과 잠금 순서를 맞춤
        for (OrderItem orderItem : getItemsInProductOrder(order)) {
            if (!cancelQuantities.containsKey(orderItem.getId())) {
                continue;
            }

            Integer requestedQuantity = cancelQuantities.get(orderItem.getId());

            orderItem.cancelQuantity(requestedQuantity);
            inventoryService.restore(orderItem.getProduct().getId(), requestedQuantity);
        }

        // 재고 복원이 끝났으므로 주문 상태만 변경
        if (order.isFullyCanceled()) {
            order.cancel();
        }
    }

    /**
     * 본인 주문의 품목과 최신 결제·배송·환불 정보를 함께 조회한다.
     *
     * @param userId 조회하는 사용자 ID
     * @param orderId 조회할 주문 ID
     * @throws BusinessException 주문 ID가 올바르지 않으면 INVALID_INPUT,
     *         주문이 없으면 ORDER_NOT_FOUND,
     *         다른 사용자의 주문이면 ORDER_ACCESS_DENIED
     */
    @Transactional(readOnly = true)
    public GetOrderDetailResponse getOrderDetail(Long userId, Long orderId) {
        // 소유권을 먼저 확인하여 다른 사용자의 결제·배송·환불 정보 조회를 막음.
        Order order = orderService.getOwnedOrderWithItems(userId, orderId);

        // 결제 기록이 있으면 READY 상태도 반환
        Payment payment = paymentService.findLatestPayment(orderId).orElse(null);

        // TODO(#169): 배송 정보 조회 메서드 구현 후 실제 조회로 교체
        Delivery delivery = null;
        // delivery = deliveryService.findDelivery(orderId).orElse(null); <- 실제 조회

        // TODO(#169): 환불 정보 조회 메서드 구현 후 실제 조회로 교체
        Refund refund = null;
        // refund = refundService.findLatestRefund(orderId).orElse(null); <- 실제 조회

        // 지연 로딩이 필요한 품목과 배송지 변환까지 트랜잭션 안에서 완료
        return GetOrderDetailResponse.from(order, payment, delivery, refund);
    }

    private List<OrderItem> getItemsInProductOrder(Order order) {
        // 여러 상품을 처리하는 모든 요청의 잠금 순서를 맞춰 교착 가능성을 줄임
        return order.getOrderItems().stream()
                .sorted(Comparator.comparing(item ->
                        item.getProduct().getId()))
                .toList();
    }

    // ex) "서울특별시 강남구 테헤란로 123" → "집 · 서울특별시 강남구"
    private String toAddressSummary(GetUserAddressResponse address) {
        String[] parts = address.address().trim().split("\\s+");
        String region = parts.length >= 2 ? parts[0] + " " + parts[1] : address.address();
        return address.place() + " · " + region;
    }

    private CreateOrderResult createOrderInTransaction(Long userId, CreateOrderRequest request) {

        // 이전 요청이 성공했다면 확인 정보가 삭제됐어도 기존 주문을 반환
        Optional<Order> existingOrder = orderService.findOwnedOrderByQuoteId(userId, request.quoteId());

        if (existingOrder.isPresent()) {
            return toExistingOrderResult(existingOrder.get());
        }

        // 신규 주문에 대해서만 구매 확인 정보를 검증
        OrderConfirmation confirmation = orderService
                .validateConfirmation(userId, request.quoteId(), request.confirmationId());

        // 확인 정보에 저장된 채팅방이 현재 사용자 소유인지 확인
        ChatRoom chatRoom = chatRoomService.getOwnedChatRoom(confirmation.roomId(), userId);

        // 구매 확인 이후 견적이 만료되거나 변경됐을 수 있으므로 다시 검증
        Quote quote = quoteService.validateForOrder(confirmation.roomId(), userId, request.quoteId());

        orderService.validateNoPendingOrder(userId);

        Map<Long, Product> productsById = validateQuoteItems(quote);

        User user = userService.getUser(userId);

        UserAddress defaultAddress = userAddressService.findDefaultAddress(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, "기본 배송지가 없습니다."));

        // 회원 배송지가 변경돼도 주문 시점의 배송 정보는 유지되도록 값을 복사
        ShippingAddress shippingAddress = ShippingAddress.of(
                defaultAddress.getRecipientName(),
                defaultAddress.getRecipientPhone(),
                defaultAddress.getZipCode(),
                defaultAddress.getAddress()
        );

        Order order = Order.of(
                user,
                quote.quoteId(),
                quote.menuName(),
                ActorType.USER,
                chatRoom,
                quote.totalAmount(),
                shippingAddress
        );

        // 상품 연결은 현재 상품으로, 구매 내역은 확인한 견적의 값으로 저장
        for (QuoteItem item : quote.items()) {
            OrderItem.of(
                    order,
                    productsById.get(item.productId()),
                    item.productName(),
                    item.productUnit(),
                    item.unitPrice(),
                    item.quantity(),
                    item.lineAmount()
            );
        }

        Order savedOrder = orderService.createOrder(order);

        // 다른 재고 처리와 잠금 순서를 맞추기 위해 상품 ID 오름차순으로 선점
        for (OrderItem item : getItemsInProductOrder(savedOrder)) {
            inventoryService.reserve(item.getProduct().getId(), item.getQuantity());
        }

        // 주문·재고와 함께 저장해 결제 준비에 실패하면 전체가 롤백되도록 함
        Payment payment = paymentService.createPayment(savedOrder.getId(), savedOrder.getTotalAmount());

        orderService.deleteConfirmationAfterCommit(request.confirmationId());

        return new CreateOrderResult(CreateOrderResponse.from(
                savedOrder, savedOrder.getOrderItems(), payment.getPortoneId()),
                true);
    }

    private Map<Long, Product> validateQuoteItems(Quote quote) {

        List<QuoteItem> items = quote.items();

        if (items == null || items.isEmpty() || quote.totalAmount() == null || quote.totalAmount() < 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "견적 품목과 주문 금액을 확인해 주세요.");
        }

        for (QuoteItem item : items) {
            if (item == null || item.productId() == null
                    || item.unitPrice() == null || item.unitPrice() < 0
                    || item.lineAmount() == null || item.lineAmount() < 0) {

                throw new BusinessException(ErrorCode.INVALID_INPUT, "견적 품목 정보가 올바르지 않습니다.");
            }

            if (item.quantity() == null || item.quantity() < 1) {
                throw new BusinessException(ErrorCode.INVALID_QUANTITY);
            }
        }

        // 품목마다 조회하지 않고 필요한 상품을 한 번에 가져옴
        List<Long> productIds = items.stream()
                .map(QuoteItem::productId)
                .distinct()
                .toList();

        Map<Long, Product> productsById = productService.findProducts(productIds)
                .stream()
                .collect(Collectors.toMap(Product::getId, product -> product));

        long calculatedTotal = 0L;

        try {
            for (QuoteItem item : items) {
                Product product = productsById.get(item.productId());

                if (product == null) {
                    throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
                }

                if (!product.getPrice().equals(item.unitPrice())) {
                    throw new BusinessException(ErrorCode.PRICE_CHANGED);
                }

                // Long 범위를 넘는 계산 결과가 잘못된 금액으로 저장되지 않도록 함.
                long calculatedLineAmount = Math.multiplyExact(item.unitPrice(), item.quantity().longValue());

                if (calculatedLineAmount != item.lineAmount()) {
                    throw new BusinessException(ErrorCode.INVALID_INPUT, "견적 품목 금액이 일치하지 않습니다.");
                }

                calculatedTotal = Math.addExact(calculatedTotal, calculatedLineAmount);
            }
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "견적 금액이 허용 범위를 초과했습니다.");
        }

        if (calculatedTotal != quote.totalAmount()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "견적 합계 금액이 일치하지 않습니다.");
        }

        return productsById;
    }

    private boolean isUniqueViolation(Throwable exception) {
        Throwable cause = exception;

        while (cause != null) {
            // PostgreSQL의 유니크 제약 위반 코드로 판별
            if (cause instanceof SQLException sqlException && "23505".equals(sqlException.getSQLState())) {
                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }

    private CreateOrderResult toExistingOrderResult(Order order) {
        // 재요청마다 결제를 새로 만들지 않고 이미 발급된 결제 번호를 반환한다.
        Payment payment = paymentService.getLatestPayment(order.getId());

        return new CreateOrderResult(
                CreateOrderResponse.from(
                        order,
                        order.getOrderItems(),
                        payment.getPortoneId()
                ),
                false
        );
    }
}
