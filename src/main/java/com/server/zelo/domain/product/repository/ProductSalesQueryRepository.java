package com.server.zelo.domain.product.repository;

import static com.server.zelo.domain.order.entity.QOrder.order;
import static com.server.zelo.domain.order.entity.QOrderItem.orderItem;

import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.server.zelo.domain.order.entity.StockState;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

// 인기 상품을 DB로 계산함. 평소엔 Redis 순위를 쓰고 Redis가 안 될 때만 씀
@Repository
@RequiredArgsConstructor
public class ProductSalesQueryRepository {

    private final JPAQueryFactory queryFactory;

    // since 이후 주문 중 결제돼서 차감된 품목만 "주문 수량 - 취소 수량"을 상품별로 더함 (전량 취소는 RESTORED라 빠짐)
    // 상품 id → 판매 수량. 많이 팔린 순, 같으면 id 순
    public Map<Long, Long> findTopSince(LocalDateTime since, int limit) {
        NumberExpression<Long> sold = orderItem.quantity.subtract(orderItem.canceledQuantity).sumLong();
        Map<Long, Long> top = new LinkedHashMap<>();
        queryFactory.select(orderItem.product.id, sold)
                .from(orderItem)
                .join(orderItem.order, order)
                .where(orderItem.stockState.eq(StockState.DEDUCTED), order.createdAt.goe(since))
                .groupBy(orderItem.product.id)
                .having(sold.gt(0))
                .orderBy(sold.desc(), orderItem.product.id.asc())
                .limit(limit)
                .fetch()
                .forEach(row -> top.put(row.get(orderItem.product.id), row.get(sold)));
        return top;
    }
}