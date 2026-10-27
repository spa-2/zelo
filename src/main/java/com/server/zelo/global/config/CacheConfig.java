package com.server.zelo.global.config;

import com.server.zelo.domain.product.dto.response.GetCategoryResponse;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.model.ProductDetail;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

// 테스트는 캐시가 테스트끼리 남아서 결과가 꼬이지 않게 zelo.cache.enabled=false 로 끔
// 캐시를 트랜잭션 바깥에서 처리해서 관리자 수정이 커밋된 뒤에 상세 캐시를 지움 (커밋 전에 지우면 옛 값이 다시 캐시될 수 있음)
@Configuration
@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "zelo.cache.enabled", havingValue = "true", matchIfMissing = true)
public class CacheConfig {

    public static final String CATEGORIES = "categories";
    public static final String INGREDIENT_UNITS = "ingredientUnits";
    public static final String PRODUCT_DETAIL = "productDetail";

    // 카테고리·재료는 바꾸는 API가 없어서 시드나 DB를 직접 바꿨을 때만 이 시간 뒤에 반영됨
    // 상품 상세는 관리자 API에서 바꿀 때 바로 지움
    private static final Duration TTL = Duration.ofHours(1);

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory redisConnectionFactory, JsonMapper jsonMapper) {
        // 캐시마다 타입을 정해서 JSON으로 저장 (타입 정보를 JSON에 같이 넣지 않으려고)
        JavaType categories = jsonMapper.getTypeFactory().constructCollectionType(List.class, GetCategoryResponse.class);
        // 프롬프트에 같은 순서로 들어가야 해서 LinkedHashMap으로 읽음
        JavaType ingredientUnits = jsonMapper.getTypeFactory()
                .constructMapType(LinkedHashMap.class, String.class, BaseUnit.class);
        JavaType productDetail = jsonMapper.getTypeFactory().constructType(ProductDetail.class);
        return RedisCacheManager.builder(redisConnectionFactory)
                .withCacheConfiguration(CATEGORIES, cacheConfiguration(jsonMapper, categories))
                .withCacheConfiguration(INGREDIENT_UNITS, cacheConfiguration(jsonMapper, ingredientUnits))
                .withCacheConfiguration(PRODUCT_DETAIL, cacheConfiguration(jsonMapper, productDetail))
                .build();
    }

    private RedisCacheConfiguration cacheConfiguration(JsonMapper jsonMapper, JavaType type) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(TTL)
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(jsonMapper, type)));
    }
}