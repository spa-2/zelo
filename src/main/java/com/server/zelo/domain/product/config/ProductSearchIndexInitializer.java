package com.server.zelo.domain.product.config;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 상품 검색은 name, brand에 LIKE '%검색어%'라 일반 인덱스를 못 탐. pg_trgm GIN 인덱스로 3글자 이상 검색을 빠르게 함
// ddl-auto로는 확장이랑 GIN 인덱스를 못 만들어서 앱 시작 때 직접 만듦 (이미 있으면 건너뜀)
// 2글자 검색어(김치 등)는 3글자 단위로 안 쪼개져서 인덱스를 못 탐
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductSearchIndexInitializer implements ApplicationRunner {
    // IntelliJ는 pg_trgm이 깔린 걸 몰라서 gin_trgm_ops를 못 찾는다고 표시함
    @SuppressWarnings("SqlResolve")
    private static final List<String> STATEMENTS = List.of(
            "create extension if not exists pg_trgm",
            "create index if not exists idx_products_name_trgm on products using gin (name gin_trgm_ops)",
            "create index if not exists idx_products_brand_trgm on products using gin (brand gin_trgm_ops)");

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            STATEMENTS.forEach(jdbcTemplate::execute);
        } catch (DataAccessException e) {
            // 인덱스가 없어도 검색은 되니까 앱은 그대로 띄움
            log.warn("상품 검색 인덱스 생성 실패 error={}", e.getMessage());
        }
    }
}