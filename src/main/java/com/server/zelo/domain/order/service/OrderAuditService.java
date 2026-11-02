package com.server.zelo.domain.order.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.domain.order.entity.ActorType;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderAuditAction;
import com.server.zelo.domain.order.entity.OrderAuditLog;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.repository.OrderAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderAuditService {

    private final OrderAuditLogRepository orderAuditLogRepository;

    // 주문 변경과 같은 트랜잭션에서만 저장해, 주문 변경이 롤백되면 기록도 같이 롤백되게 함
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordStateChange(Order order, OrderAuditAction action, OrderStatus previousStatus,
                                  ActorType actorType, Long actorId, String reason) {
        orderAuditLogRepository.save(
                OrderAuditLog.stateChanged(order, action, previousStatus, actorType, actorId, reason));
    }

    // 주문 트랜잭션이 롤백된 뒤에도 남도록 따로 커밋함
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejection(Long userId, String quoteId, BusinessException exception) {
        orderAuditLogRepository.save(OrderAuditLog.rejected(
                userId, quoteId, exception.getErrorCode().name(), exception.getMessage()));
    }
}
