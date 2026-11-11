package com.server.zelo.global.seed;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LoadTestExtraStockSeedLoaderTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private LoadTestExtraStockSeedLoader loadTestExtraStockSeedLoader;

    @Test
    void 추가_재고를_주지_않으면_재고를_바꾸지_않는다() {
        // given
        ReflectionTestUtils.setField(loadTestExtraStockSeedLoader, "extraStock", 0);

        // when
        loadTestExtraStockSeedLoader.load();

        // then
        then(jdbcTemplate).should(never()).update(anyString(), any(Object[].class));
    }

    @Test
    void 추가_재고를_주면_재고를_그_값으로_맞춘다() {
        // given
        ReflectionTestUtils.setField(loadTestExtraStockSeedLoader, "extraStock", 100_000);

        // when
        loadTestExtraStockSeedLoader.load();

        // then
        then(jdbcTemplate).should().update(anyString(), eq(100_000), eq(100_000),
                eq(new String[]{"김치", "밥", "계란", "대파"}));
    }
}
