package com.server.zelo.domain.product.service;

import com.server.zelo.domain.product.repository.PopularProductRepository;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 인기 상품은 없어도 주문·화면이 돌아가야 해서 Redis가 안 되면 로그만 남김
@Slf4j
@Service
@RequiredArgsConstructor
public class PopularProductService {

    private final PopularProductRepository popularProductRepository;

    // 주문이 롤백되면 안 센 걸로 해야 해서 커밋된 뒤에 Redis에 반영
    public void record(Long productId, int quantity) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            increase(productId, quantity);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                increase(productId, quantity);
            }
        });
    }

    // 상품 id → 최근 7일 판매 수량. 많이 팔린 순서를 지킴
    public Map<Long, Long> findTop(int limit) {
        try {
            Map<Long, Long> top = new LinkedHashMap<>();
            popularProductRepository.findTop(LocalDate.now(), limit)
                    .forEach(tuple -> top.put(Long.valueOf(tuple.getValue()), tuple.getScore().longValue()));
            return top;
        } catch (DataAccessException e) {
            log.warn("인기 상품 조회 실패 error={}", e.getMessage());
            return Map.of();
        }
    }

    private void increase(Long productId, int quantity) {
        try {
            popularProductRepository.increase(productId, quantity, LocalDate.now());
        } catch (DataAccessException e) {
            log.warn("인기 상품 기록 실패 productId={}, error={}", productId, e.getMessage());
        }
    }
}