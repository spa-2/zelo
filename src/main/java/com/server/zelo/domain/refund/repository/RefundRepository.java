package com.server.zelo.domain.refund.repository;

import com.server.zelo.domain.refund.dto.response.OrderRefundAmount;
import com.server.zelo.domain.refund.entity.Refund;
import com.server.zelo.domain.refund.entity.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
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
}