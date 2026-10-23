package com.server.zelo.domain.order.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.service.ChatRoomService;
import com.server.zelo.domain.inventory.service.InventoryService;
import com.server.zelo.domain.order.dto.request.CreateOrderConfirmationRequest;
import com.server.zelo.domain.order.dto.response.CreateOrderConfirmationResponse;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderItem;
import com.server.zelo.domain.quote.mock.service.MockQuoteService;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.user.dto.response.GetUserAddressResponse;
import com.server.zelo.domain.user.service.UserAddressService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class OrderCommandService {
    private final OrderService orderService;
    private final MockQuoteService quoteService;
    private final ChatRoomService chatRoomService;
    private final InventoryService inventoryService;
    private final UserAddressService userAddressService;

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

    public CreateOrderConfirmationResponse createOrder(Long userId, CreateOrderConfirmationRequest request) {
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
}
