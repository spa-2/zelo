package com.server.zelo.domain.order.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository orderRepository;

    public Order getOrderWithItems(Long orderId) {
        return orderRepository.findWithOrderItemsById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }

    @Transactional
    public Order getOrderWithItemsForUpdate(Long orderId) {
        // 상태를 검사하기 전에 주문을 잠가 동시 요청이 같은 상태로 처리하지 못하게 한다.
        orderRepository.findForUpdateById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        return getOrderWithItems(orderId);
    }
}
