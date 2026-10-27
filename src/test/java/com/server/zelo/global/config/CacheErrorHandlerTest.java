package com.server.zelo.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.server.zelo.domain.product.dto.response.GetCategoryResponse;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.entity.ProductStatus;
import com.server.zelo.domain.product.repository.CategoryRepository;
import com.server.zelo.domain.product.repository.IngredientRepository;
import com.server.zelo.domain.product.repository.ProductKeywordRepository;
import com.server.zelo.domain.product.repository.ProductRepository;
import com.server.zelo.domain.product.service.CategoryService;
import com.server.zelo.domain.product.service.ProductService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

// 아무것도 안 떠 있는 포트에 붙여서 Redis가 죽었을 때도 DB로 처리되는지 확인
@SpringJUnitConfig({CacheConfig.class, CategoryService.class, ProductService.class, CacheErrorHandlerTest.BrokenRedisConfig.class})
class CacheErrorHandlerTest {

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private ProductService productService;

    @MockitoBean
    private CategoryRepository categoryRepository;

    @MockitoBean
    private ProductRepository productRepository;

    @MockitoBean
    private IngredientRepository ingredientRepository;

    @MockitoBean
    private ProductKeywordRepository productKeywordRepository;

    @Test
    void 레디스가_안_돼도_카테고리는_DB에서_조회한다() {
        // given
        Category category = Category.of("국·반찬", true);
        ReflectionTestUtils.setField(category, "id", 1L);
        given(categoryRepository.findAllByOrderByIdAsc()).willReturn(List.of(category));

        // when
        List<GetCategoryResponse> result = categoryService.getCategories();

        // then
        assertThat(result).extracting(GetCategoryResponse::name).containsExactly("국·반찬");
        then(categoryRepository).should(times(1)).findAllByOrderByIdAsc();
    }

    @Test
    void 레디스가_안_돼도_판매중지는_된다() {
        // given
        Category category = Category.of("국·반찬", true);
        Product product = Product.of("P-1", Ingredient.of(category, "김치", BaseUnit.G),
                "브랜드 A", "백김치 1kg", null, 8900L, 1000, "1kg");
        given(productRepository.findByProductCode("P-1")).willReturn(Optional.of(product));

        // when
        Product result = productService.stopSale("P-1");

        // then
        assertThat(result.getStatus()).isEqualTo(ProductStatus.STOP_SALE);
    }

    @Configuration
    static class BrokenRedisConfig {

        @Bean
        RedisConnectionFactory redisConnectionFactory() {
            return new LettuceConnectionFactory("localhost", 1);
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }
}