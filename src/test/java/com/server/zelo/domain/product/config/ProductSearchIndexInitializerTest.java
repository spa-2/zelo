package com.server.zelo.domain.product.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class ProductSearchIndexInitializerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private ProductSearchIndexInitializer productSearchIndexInitializer;

    @Test
    void 확장과_상품명_브랜드_인덱스를_만든다() {
        // when
        productSearchIndexInitializer.run(new DefaultApplicationArguments());

        // then
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        then(jdbcTemplate).should(times(3)).execute(captor.capture());
        assertThat(captor.getAllValues()).satisfiesExactly(
                sql -> assertThat(sql).contains("pg_trgm"),
                sql -> assertThat(sql).contains("idx_products_name_trgm"),
                sql -> assertThat(sql).contains("idx_products_brand_trgm"));
    }

    @Test
    void 인덱스를_못_만들어도_앱은_뜬다() {
        // given
        willThrow(new DataAccessResourceFailureException("permission denied")).given(jdbcTemplate).execute(anyString());

        // when, then
        assertThatCode(() -> productSearchIndexInitializer.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
        then(jdbcTemplate).should(times(1)).execute(anyString());
    }
}