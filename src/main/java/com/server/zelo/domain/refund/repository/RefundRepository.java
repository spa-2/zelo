package com.server.zelo.domain.refund.repository;

import com.server.zelo.domain.refund.dto.response.OrderRefundAmount;
import com.server.zelo.domain.refund.dto.response.RefundFindTarget;
import com.server.zelo.domain.refund.entity.Refund;
import com.server.zelo.domain.refund.entity.RefundStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefundRepository extends JpaRepository<Refund, Long> {

    boolean existsByPaymentIdAndStatus(Long paymentId, RefundStatus status);

    Optional<Refund> findByPaymentIdAndStatus(Long paymentId, RefundStatus status);

    Optional<Refund> findTopByPaymentIdOrderByIdDesc(Long paymentId);

    List<Refund> findAllByPaymentIdAndStatus(Long paymentId, RefundStatus status);

    // 환불은 결제 ID만 갖고 있어서 결제를 ID로 이어 주문별 완료 환불 금액을 한 번에 더한다
    @Query("""
            select new com.server.zelo.domain.refund.dto.response.OrderRefundAmount(p.orderId, sum(r.itemsAmount))
            from Refund r join Payment p on p.id = r.paymentId
            where p.orderId in :orderIds and r.status = :status
            group by p.orderId
            """)
    List<OrderRefundAmount> findRefundedAmountsByOrderIds(@Param("orderIds") Collection<Long> orderIds,
                                                          @Param("status") RefundStatus status);

    // 결과를 모른 채 오래 남은 환불을 결제와 묶어 주문 ID·포트원 결제 ID로 꺼낸다
    @Query("""
            select new com.server.zelo.domain.refund.dto.response.RefundFindTarget(r.id, p.orderId, p.portoneId)
            from Refund r join Payment p on p.id = r.paymentId
            where r.status = :status and r.updatedAt < :cutoff and r.id > :afterId
            order by r.id asc
            """)
    List<RefundFindTarget> findTargets(@Param("status") RefundStatus status,
                                       @Param("cutoff") LocalDateTime cutoff,
                                       @Param("afterId") Long afterId,
                                       Pageable pageable);
}