package com.server.zelo.domain.order.service;

import com.server.zelo.common.api.PageResponse;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.order.dto.request.SearchOrderCondition;
import com.server.zelo.domain.order.dto.response.GetOrderSummaryResponse;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.model.OrderConfirmation;
import com.server.zelo.domain.order.repository.OrderConfirmationRepository;
import com.server.zelo.domain.order.repository.OrderRepository;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {
    private static final int EXPIRATION_BATCH_SIZE = 100;

    private final OrderRepository orderRepository;
    private final OrderConfirmationRepository orderConfirmationRepository;

    public Order getOrderWithItems(Long orderId) {
        return orderRepository.findWithOrderItemsById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }

    @Transactional
    public Order getOrderWithItemsForUpdate(Long orderId) {
        // 상태를 검사하기 전에 주문을 잠가 동시 요청이 같은 상태로 처리하지 못하게 함.
        orderRepository.findForUpdateById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        return getOrderWithItems(orderId);
    }

    public Page<Order> getMyOrders(
            Long userId,
            SearchOrderCondition condition
    ) {

        /* 요청의 정렬 조건과 관계없이 최신순으로 조회하고,
         * 생성 시각이 같으면 ID 내림차순으로 정렬해 페이지 간 순서를 일정하게 유지
         */
        Pageable sortedPageable = PageRequest.of(
                condition.page(),
                condition.size(),
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")
                )
        );

        // 사용자 ID 기준 조회. 주문 상태 요청값 여부에 따른 조회방식 분기
        return condition.status() == null
                ? orderRepository.findByUser_Id(sortedPageable, userId)
                : orderRepository.findByUser_IdAndStatus(sortedPageable, userId, condition.status());
    }

    // OrderConfirmationRepository랑 OrderCommandService 연결
    @Transactional
    public void createConfirmation(
            String confirmationId,
            Long userId,
            Long roomId,
            String quoteId,
            LocalDateTime expiresAt
    ) {
        orderConfirmationRepository.save(confirmationId, userId, roomId, quoteId, expiresAt);
    }

    public OrderConfirmation validateConfirmation(Long userId, String quoteId, String confirmationId) {

        OrderConfirmation confirmation = orderConfirmationRepository
                .findById(confirmationId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVALID_INPUT,
                        "구매 확인 정보가 없거나 만료되었습니다."
                ));

        // 다른 사용자가 발급받은 확인 번호로 주문하지 못하도록 검증
        if (!userId.equals(confirmation.userId())) {
            throw new BusinessException(ErrorCode.NO_AUTHORITY);
        }

        // 구매 확인을 거친 견적과 실제 주문하려는 견적이 같은지 검증
        if (!quoteId.equals(confirmation.quoteId())) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "구매 확인 정보와 견적이 일치하지 않습니다."
            );
        }

        return confirmation;
    }

    // 주문이 없는 경우도 같은 예외로 응답해 다른 사용자의 주문 존재 여부를 노출하지 않음.
    public void validateOwner(Long orderId, Long userId) {
        if (!orderRepository.existsByIdAndUser_Id(orderId, userId)) {
            throw new BusinessException(ErrorCode.ORDER_ACCESS_DENIED);
        }
    }

    public Optional<Order> findOwnedOrderByQuoteId(Long userId, String quoteId) {
        return orderRepository.findByQuoteId(quoteId)
                .map(order -> {
                    // 다른 사용자의 주문 정보가 반환되지 않도록 소유권을 확인
                    if (!userId.equals(order.getUser().getId())) {
                        throw new BusinessException(ErrorCode.NO_AUTHORITY);
                    }

                    return order;
                });
    }

    // 채팅이 견적 카드마다 주문됐는지 표시할 때 사용. 카드 수만큼 조회하지 않도록 한 번에 조회
    public Set<String> findOrderedQuoteIds(Collection<String> quoteIds) {
        return new HashSet<>(orderRepository.findQuoteIdsByQuoteIdIn(quoteIds));
    }

    // 선점 재고가 여러 결제 대기 주문에 묶이지 않도록 신규 주문 생성을 제한
    public void validateNoPendingOrder(Long userId) {
        if (orderRepository.existsByUser_IdAndStatus(userId, OrderStatus.PAYMENT_PENDING)) {
            throw new BusinessException(ErrorCode.PENDING_ORDER_EXISTS);
        }
    }

    @Transactional
    public Order createOrder(Order order) {
        return orderRepository.save(order);
    }

    public void deleteConfirmationAfterCommit(String confirmationId) {
        // 주문 저장과 재고 선점이 모두 커밋된 경우에만 확인 정보를 삭제한다.
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            orderConfirmationRepository.deleteById(confirmationId);
                        } catch (RuntimeException exception) {
                            // 주문은 이미 저장됐으므로 삭제 실패는 기록하고 기존 TTL로 정리한다.
                            log.warn("주문 생성 후 구매 확인 정보 삭제 실패", exception);
                        }
                    }
                }
        );
    }

    /**
     * 본인의 주문과 주문 상품을 함께 조회한다.
     *
     * @param userId 조회하는 사용자 ID
     * @param orderId 조회할 주문 ID
     * @throws BusinessException 주문 ID가 올바르지 않으면 INVALID_INPUT,
     *         주문이 없으면 ORDER_NOT_FOUND,
     *         다른 사용자의 주문이면 ORDER_ACCESS_DENIED
     */
    public Order getOwnedOrderWithItems(Long userId, Long orderId) {
        if (orderId == null || orderId < 1) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // 기존 품목 동시 조회를 재사용하고, 없는 주문은 404로 구분
        Order order = getOrderWithItems(orderId);

        // 결제·배송·환불 정보를 조회하기 전에 주문 소유권을 확인
        if (!userId.equals(order.getUser().getId())) {
            throw new BusinessException(ErrorCode.ORDER_ACCESS_DENIED);
        }

        return order;
    }

    // 한 번의 실행에서 처리할 주문 수를 제한하고, 취소 가능 여부는 잠금 후 다시 확인
    public List<Long> findExpiredOrderIds(LocalDateTime cutoff) {
        return orderRepository.findExpiredOrderIds(
                OrderStatus.PAYMENT_PENDING,
                cutoff,
                List.of(PaymentStatus.CONFIRM_PENDING, PaymentStatus.PAID),
                PageRequest.of(0, EXPIRATION_BATCH_SIZE)
        );
    }

    public boolean isExpiredOrder(Long orderId, LocalDateTime cutoff) {
        return orderRepository.existsExpiredOrder(
                orderId,
                OrderStatus.PAYMENT_PENDING,
                cutoff,
                List.of(PaymentStatus.CONFIRM_PENDING, PaymentStatus.PAID)
        );
    }
}
