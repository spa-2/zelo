package com.server.zelo.domain.product.repository;

import com.server.zelo.domain.inventory.entity.Inventory;
import com.server.zelo.domain.product.dto.request.ProductSort;
import com.server.zelo.domain.product.entity.*;
import com.server.zelo.global.config.AuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(AuditingConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProductSearchRepositoryTest {

    // 로컬 DB에 시드 상품이 있어서 테스트 상품만 걸리게 이름에 이 값을 넣고 이걸로 검색함
    private static final String TAG = "검색테스트9000501";

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ProductRepository productRepository;

    private Category parent;
    private Category kimchiCategory;

    @BeforeEach
    void setUp() {
        parent = Category.of(TAG + "-국반찬", true);
        kimchiCategory = Category.of(parent, TAG + "-김치");
        Category sauceCategory = Category.of(TAG + "-양념", true);
        Ingredient kimchi = Ingredient.of(kimchiCategory, TAG + "-김치", BaseUnit.G);
        Ingredient sauce = Ingredient.of(sauceCategory, TAG + "-고추장", BaseUnit.G);
        entityManager.persist(parent);
        entityManager.persist(kimchiCategory);
        entityManager.persist(sauceCategory);
        entityManager.persist(kimchi);
        entityManager.persist(sauce);

        persist("SEARCH-9000501", kimchi, "브랜드 A", TAG + " 백김치 1kg", 8900L, 10);
        persist("SEARCH-9000502", kimchi, "브랜드 B", TAG + " 포기김치 500g", 5900L, 0);
        persist("SEARCH-9000503", sauce, "브랜드 A", TAG + " 고추장 500g", 7900L, 5);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void 키워드가_이름에_들어간_상품을_찾는다() {
        // when
        Page<Product> result = search(TAG + " 백김치", null, false, ProductSort.SALES);

        // then
        assertThat(result.getContent()).extracting(Product::getProductCode).containsExactly("SEARCH-9000501");
    }

    @Test
    void 대분류로_찾으면_소분류_상품도_나온다() {
        // when
        Page<Product> result = search(TAG, parent.getId(), false, ProductSort.PRICE_ASC);

        // then
        assertThat(result.getContent()).extracting(Product::getProductCode)
                .containsExactly("SEARCH-9000502", "SEARCH-9000501");
    }

    @Test
    void 소분류로_찾으면_그_소분류_상품만_나온다() {
        // when
        Page<Product> result = search(TAG, kimchiCategory.getId(), false, ProductSort.PRICE_DESC);

        // then
        assertThat(result.getContent()).extracting(Product::getProductCode)
                .containsExactly("SEARCH-9000501", "SEARCH-9000502");
    }

    @Test
    void 재고_있는_것만_찾으면_품절_상품은_빠진다() {
        // when
        Page<Product> result = search(TAG, null, true, ProductSort.PRICE_ASC);

        // then
        assertThat(result.getContent()).extracting(Product::getProductCode)
                .containsExactly("SEARCH-9000503", "SEARCH-9000501");
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    void 상품_코드로_찾는다() {
        // when
        Product product = productRepository.findByProductCode("SEARCH-9000503").orElseThrow();

        // then
        assertThat(product.getCategory().getName()).isEqualTo(TAG + "-양념");
    }

    @Test
    void 자동완성은_같은_이름을_한_번만_많이_팔린_순으로_주고_판매중지는_뺀다() {
        // given
        Product baek = productRepository.findByProductCode("SEARCH-9000501").orElseThrow();
        persist("SEARCH-9000504", baek.getIngredient(), "브랜드 C", TAG + " 백김치 1kg", 9900L, 3);
        productRepository.increaseSalesCount(productRepository.findByProductCode("SEARCH-9000502").orElseThrow().getId(), 5);
        productRepository.findByProductCode("SEARCH-9000503").orElseThrow().stopSale();
        entityManager.flush();
        entityManager.clear();

        // when
        List<String> names = productRepository.findNames(TAG, ProductStatus.ON_SALE, Limit.of(10));

        // then
        assertThat(names).containsExactly(TAG + " 포기김치 500g", TAG + " 백김치 1kg");
    }

    @Test
    void 이스케이프한_밑줄은_글자_그대로_찾는다() {
        // given
        Product baek = productRepository.findByProductCode("SEARCH-9000501").orElseThrow();
        persist("SEARCH-9000505", baek.getIngredient(), "브랜드 C", TAG + "_특가 백김치", 7900L, 3);
        entityManager.flush();
        entityManager.clear();

        // when
        List<String> names = productRepository.findNames(TAG + "\\_", ProductStatus.ON_SALE, Limit.of(10));
        Page<Product> products = search(TAG + "\\_", null, false, ProductSort.SALES);

        // then
        assertThat(names).containsExactly(TAG + "_특가 백김치");
        assertThat(products.getContent()).extracting(Product::getProductCode).containsExactly("SEARCH-9000505");
    }

    private Page<Product> search(String keyword, Long categoryId, boolean inStock, ProductSort sort) {
        return productRepository.search(keyword, categoryId, inStock, ProductStatus.ON_SALE,
                PageRequest.of(0, 20, sort.toSort()));
    }

    private void persist(String code, Ingredient ingredient, String brand, String name, Long price, int stock) {
        Product product = Product.of(code, ingredient, brand, name, null, price, 1, "1개");
        entityManager.persist(product);
        entityManager.persist(Inventory.of(product, stock));
    }
}