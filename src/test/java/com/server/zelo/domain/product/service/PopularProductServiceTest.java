package com.server.zelo.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.server.zelo.domain.product.repository.PopularProductRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class PopularProductServiceTest {

    @Mock
    private PopularProductRepository popularProductRepository;

    @InjectMocks
    private PopularProductService popularProductService;

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 트랜잭션_안이면_커밋된_뒤에_센다() {
        // given
        TransactionSynchronizationManager.initSynchronization();

        // when
        popularProductService.record(1L, 3);

        // then
        then(popularProductRepository).should(never()).increase(any(), anyInt(), any());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        then(popularProductRepository).should().increase(eq(1L), eq(3), any());
    }

    @Test
    void 롤백되면_안_센다() {
        // given
        TransactionSynchronizationManager.initSynchronization();

        // when
        popularProductService.record(1L, 3);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        // then
        then(popularProductRepository).should(never()).increase(any(), anyInt(), any());
    }

    @Test
    void Redis가_안_되면_기록은_건너뛰고_조회는_빈_목록을_준다() {
        // given
        willThrow(new QueryTimeoutException("timeout")).given(popularProductRepository).increase(any(), anyInt(), any());
        given(popularProductRepository.findTop(any(), anyInt())).willThrow(new QueryTimeoutException("timeout"));

        // when & then
        assertThatCode(() -> popularProductService.record(1L, 3)).doesNotThrowAnyException();
        assertThat(popularProductService.findTop(10)).isEmpty();
    }

    @Test
    void 많이_팔린_순서를_지켜서_상품_id와_수량을_준다() {
        // given
        given(popularProductRepository.findTop(any(), eq(10)))
                .willReturn(List.of(TypedTuple.of("9", 5.0), TypedTuple.of("2", 3.0)));

        // when
        Map<Long, Long> top = popularProductService.findTop(10);

        // then
        assertThat(top).containsExactly(Map.entry(9L, 5L), Map.entry(2L, 3L));
    }
}