package com.server.zelo.domain.order.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.service.ChatRoomService;
import com.server.zelo.domain.inventory.service.InventoryService;
import com.server.zelo.domain.order.dto.request.CreateOrderConfirmationRequest;
import com.server.zelo.domain.order.dto.request.CreateOrderRequest;
import com.server.zelo.domain.order.dto.response.CreateOrderConfirmationResponse;
import com.server.zelo.domain.order.dto.response.CreateOrderResponse;
import com.server.zelo.domain.order.entity.ActorType;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderItem;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.entity.ShippingAddress;
import com.server.zelo.domain.order.model.CreateOrderResult;
import com.server.zelo.domain.order.model.OrderConfirmation;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import com.server.zelo.domain.quote.service.QuoteService;
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
    private final ProductService productService;
    private final ChatRoomService chatRoomService;
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
     * 주문을 취소하고, 품목별 재고 처리 상태에 따라 재고를 되돌린다.
     *
     * <p>주문 상태는 취소 시점과 관계없이 CANCELED로 바뀌지만,
     * 재고는 취소 시점까지 진행된 처리에 따라 되돌리는 방법이 다르다.
     * <ul>
     *   <li>RESERVED (결제 전 취소): 판매되지 않았으므로 선점만 해제한다.</li>
     *   <li>DEDUCTED (결제 후 환불): 판매가 확정됐으므로 실재고를 복원한다.</li>
     * </ul>
     *
     * <p>재고에 어떤 처리가 이뤄졌는지는 품목이 기록하므로 품목의 재고 상태를 기준으로 분기한다.
     * 품목 상태 전이 검증이 같은 품목의 중복 해제·복원을 막는다.
     *
     * @param orderId 취소할 주문 ID
     * @throws BusinessException 주문이 없으면 ORDER_NOT_FOUND,
     *         주문이 PAYMENT_PENDING·PAID가 아니거나 품목 상태 전이가 허용되지 않으면 INVALID_ORDER_STATUS
     */
    public void cancel(Long orderId) {
        Order order = orderService.getOrderWithItemsForUpdate(orderId);
        order.cancel(); // OrderStatus: PAYMENT_PENDING 또는 PAID → CANCELED

        for(OrderItem orderItem : getItemsInProductOrder(order)) {

            // 품목이 RESERVED면 해제
            if (orderItem.isReserved()) {
                orderItem.release(); // StockState: RESERVED -> RELEASED
                inventoryService.release(orderItem.getProduct().getId(), orderItem.getQuantity());
            }
            else {
                // DEDUCTED가 아니면 restore()의 전이 검증에서 예외 발생
                orderItem.restore();  // StockState: DEDUCTED → RESTORED
                inventoryService.restore(orderItem.getProduct().getId(), orderItem.getQuantity());
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
                        return new CreateOrderResult(
                                CreateOrderResponse.from(existingOrder, existingOrder.getOrderItems()),
                                false
                        );
                    })
            );
        }
    }

    private List<OrderItem> getItemsInProductOrder(Order order) {
        // 여러 상품을 처리하는 모든 요청의 잠금 순서를 맞춰 교착 가능성을 줄인다.
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
            Order order = existingOrder.get();

            return new CreateOrderResult(CreateOrderResponse.from(order, order.getOrderItems()), false);
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

        orderService.deleteConfirmationAfterCommit(request.confirmationId());

        return new CreateOrderResult(CreateOrderResponse.from(savedOrder, savedOrder.getOrderItems()), true);
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
}
