package com.server.zelo.domain.order.repository;

import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    // 동일 주문의 결제·실패·취소가 동시에 진행되지 않도록 주문 행을 잠금
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Order> findForUpdateById(Long id);

    // Order, OrderItem 같이 조회
    @EntityGraph(attributePaths = "orderItems")
    Optional<Order> findWithOrderItemsById(Long id);

    // 상태 조건 없이 본인 주문 조회
    Page<Order> findByUser_Id(Pageable pageable, Long userId);

    // 상태 조건을 포함해 본인 주문 조회
    Page<Order> findByUser_IdAndStatus(Pageable pageable, Long userId, OrderStatus status);

    // 주문 전체를 불러오지 않고 소유 여부만 조회
    boolean existsByIdAndUser_Id(Long id, Long userId);

    // 같은 견적의 재요청에는 기존 주문과 품목을 반환
    @EntityGraph(attributePaths = "orderItems")
    Optional<Order> findByQuoteId(String quoteId);

    boolean existsByUser_IdAndStatus(Long userId, OrderStatus status);

    // 채팅 견적 카드 여러 장의 주문 여부를 한 번에 확인. 주문 상태와 관계없이 그 견적으로 만든 주문이 있으면 포함
    @Query("select o.quoteId from Order o where o.quoteId in :quoteIds")
    List<String> findQuoteIdsByQuoteIdIn(@Param("quoteIds") Collection<String> quoteIds);

    // 확인 중이거나 결제 완료된 결제가 있는 주문은 만료 후보에서 제외
    @Query("""
        select o.id
        from Order o
        where o.status = :status
          and o.createdAt <= :cutoff
          and not exists (
              select p.id
              from Payment p
              where p.orderId = o.id
                and p.status in :excludedPaymentStatuses
          )
        order by o.createdAt asc, o.id asc
        """)
    List<Long> findExpiredOrderIds(
            @Param("status") OrderStatus status,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("excludedPaymentStatuses") List<PaymentStatus> excludedPaymentStatuses,
            Pageable pageable
    );

    // 후보 조회 이후 상태가 달라졌을 수 있으므로 취소 직전에 다시 확인
    @Query("""
        select case when count(o) > 0 then true else false end
        from Order o
        where o.id = :orderId
          and o.status = :status
          and o.createdAt <= :cutoff
          and not exists (
              select p.id
              from Payment p
              where p.orderId = o.id
                and p.status in :excludedPaymentStatuses
          )
        """)
    boolean existsExpiredOrder(
            @Param("orderId") Long orderId,
            @Param("status") OrderStatus status,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("excludedPaymentStatuses") List<PaymentStatus> excludedPaymentStatuses
    );
}