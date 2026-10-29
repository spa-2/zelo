package com.server.zelo.global.config;

import com.server.zelo.domain.product.dto.response.GetCategoryResponse;
import com.server.zelo.domain.product.entity.*;
import com.server.zelo.domain.product.model.ProductDetail;
import com.server.zelo.domain.product.repository.CategoryRepository;
import com.server.zelo.domain.product.repository.IngredientRepository;
import com.server.zelo.domain.product.repository.ProductKeywordRepository;
import com.server.zelo.domain.product.repository.ProductRepository;
import com.server.zelo.domain.product.service.CategoryService;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.product.service.PopularProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

// 실제 Redis(localhost:6379)에 캐시를 저장해서 두 번째 조회가 DB를 안 타는지 확인
@SpringJUnitConfig({CacheConfig.class, CategoryService.class, ProductService.class, CacheConfigTest.RedisTestConfig.class})
class CacheConfigTest {

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private ProductService productService;

    @Autowired
    private RedisCacheManager cacheManager;

    @MockitoBean
    private CategoryRepository categoryRepository;

    @MockitoBean
    private ProductRepository productRepository;

    @MockitoBean
    private IngredientRepository ingredientRepository;

    @MockitoBean
    private ProductKeywordRepository productKeywordRepository;

    @MockitoBean
    private PopularProductService popularProductService;

    @BeforeEach
    void setUp() {
        cacheManager.getCache(CacheConfig.CATEGORIES).clear();
        cacheManager.getCache(CacheConfig.PRODUCT_DETAIL).clear();
    }

    @Test
    void 카테고리를_두_번_조회하면_두_번째는_캐시에서_읽는다() {
        // given
        Category parent = category(1L, "국·반찬", null);
        Category child = category(2L, "김치·젓갈", parent);
        given(categoryRepository.findAllByOrderByIdAsc()).willReturn(List.of(parent, child));

        // when
        List<GetCategoryResponse> first = categoryService.getCategories();
        List<GetCategoryResponse> second = categoryService.getCategories();

        // then
        assertThat(second).isEqualTo(first);
        assertThat(second.get(0).children()).extracting(GetCategoryResponse.SubCategory::name).containsExactly("김치·젓갈");
        then(categoryRepository).should().findAllByOrderByIdAsc();
    }

    @Test
    void 상품_상세는_캐시에서_읽고_판매중지하면_캐시를_지운다() {
        // given
        Product product = Product.of("P-1", Ingredient.of(category(1L, "국·반찬", null), "김치", BaseUnit.G),
                "브랜드 A", "백김치 1kg", null, 8900L, 1000, "1kg");
        ReflectionTestUtils.setField(product, "id", 1L);
        given(productRepository.findByProductCode("P-1")).willReturn(Optional.of(product));
        given(productKeywordRepository.findAllByProduct_IdIn(List.of(1L))).willReturn(List.of());

        // when
        productService.getProductDetail("P-1");
        ProductDetail cached = productService.getProductDetail("P-1");
        productService.stopSale("P-1");
        ProductDetail afterStop = productService.getProductDetail("P-1");

        // then
        assertThat(cached.status()).isEqualTo(ProductStatus.ON_SALE);
        assertThat(afterStop.status()).isEqualTo(ProductStatus.STOP_SALE);
        then(productKeywordRepository).should(times(2)).findAllByProduct_IdIn(List.of(1L));
    }

    @Test
    void 캐시가_비었을_때_동시에_조회해도_DB는_한_번만_읽는다() throws Exception {
        // given
        Category parent = category(1L, "국·반찬", null);
        given(categoryRepository.findAllByOrderByIdAsc()).willAnswer(invocation -> {
            Thread.sleep(200);
            return List.of(parent);
        });
        CountDownLatch start = new CountDownLatch(1);

        // when
        try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
            List<CompletableFuture<List<GetCategoryResponse>>> results = IntStream.range(0, 10)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return categoryService.getCategories();
                    }, executor))
                    .toList();
            start.countDown();

            // then
            results.forEach(result -> assertThat(result.join()).hasSize(1));
        }
        then(categoryRepository).should().findAllByOrderByIdAsc();
    }

    @Test
    void 재료_단위는_순서를_지켜서_다시_읽는다() {
        // given
        Map<String, BaseUnit> units = new LinkedHashMap<>();
        units.put("쌀", BaseUnit.G);
        units.put("계란", BaseUnit.EA);
        units.put("간장", BaseUnit.ML);
        SerializationPair<Object> pair = valuePair(CacheConfig.INGREDIENT_UNITS);

        // when
        Object result = pair.read(pair.write(units));

        // then
        assertThat(result).isInstanceOf(LinkedHashMap.class).isEqualTo(units);
        assertThat(List.copyOf(((Map<?, ?>) result).keySet())).isEqualTo(List.of("쌀", "계란", "간장"));
    }

    @SuppressWarnings("unchecked")
    private SerializationPair<Object> valuePair(String cacheName) {
        RedisCacheConfiguration configuration = cacheManager.getCacheConfigurations().get(cacheName);
        return (SerializationPair<Object>) configuration.getValueSerializationPair();
    }

    private Category category(Long id, String name, Category parent) {
        Category category = parent == null ? Category.of(name, true) : Category.of(parent, name);
        ReflectionTestUtils.setField(category, "id", id);
        return category;
    }

    @Configuration
    static class RedisTestConfig {

        @Bean
        RedisConnectionFactory redisConnectionFactory() {
            return new LettuceConnectionFactory("localhost", 6379);
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }
}