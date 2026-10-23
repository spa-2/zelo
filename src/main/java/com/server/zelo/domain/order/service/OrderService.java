package com.server.zelo.domain.order.service;

import com.server.zelo.common.api.PageResponse;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.order.dto.request.SearchOrderCondition;
import com.server.zelo.domain.order.dto.response.GetOrderSummaryResponse;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.repository.OrderConfirmationRepository;
import com.server.zelo.domain.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {
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

    public PageResponse<GetOrderSummaryResponse> getMyOrders(
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
        Page<Order> orders = condition.status() == null
                ? orderRepository.findByUser_Id(sortedPageable, userId)
                : orderRepository.findByUser_IdAndStatus(sortedPageable, userId, condition.status());

        // PageResponse 변환
        return PageResponse.of(orders, GetOrderSummaryResponse::from);
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

    // 주문이 없는 경우도 같은 예외로 응답해 다른 사용자의 주문 존재 여부를 노출하지 않음.
    public void validateOwner(Long orderId, Long userId) {
        if (!orderRepository.existsByIdAndUser_Id(orderId, userId)) {
            throw new BusinessException(ErrorCode.ORDER_ACCESS_DENIED);
        }
    }
}
