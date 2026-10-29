package com.server.zelo.global.config;

import com.server.zelo.domain.product.dto.response.GetCategoryResponse;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.model.ProductDetail;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

// 테스트는 캐시가 테스트끼리 남아서 결과가 꼬이지 않게 zelo.cache.enabled=false 로 끔
// 캐시를 트랜잭션 바깥에서 처리해서 관리자 수정이 커밋된 뒤에 상세 캐시를 지움 (커밋 전에 지우면 옛 값이 다시 캐시될 수 있음)
@Slf4j
@Configuration
@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "zelo.cache.enabled", havingValue = "true", matchIfMissing = true)
public class CacheConfig implements CachingConfigurer {

    public static final String CATEGORIES = "categories";
    public static final String INGREDIENT_UNITS = "ingredientUnits";
    public static final String PRODUCT_DETAIL = "productDetail";

    // 카테고리·재료는 바꾸는 API가 없어서 시드나 DB를 직접 바꿨을 때만 이 시간 뒤에 반영됨
    // 상품 상세는 관리자 API에서 바꿀 때 바로 지움
    private static final Duration TTL = Duration.ofHours(1);

    // 락 잡은 서버가 죽어도 이 시간 뒤엔 풀리게 함
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory redisConnectionFactory, JsonMapper jsonMapper) {
        // 캐시마다 타입을 정해서 JSON으로 저장 (타입 정보를 JSON에 같이 넣지 않으려고)
        JavaType categories = jsonMapper.getTypeFactory().constructCollectionType(List.class, GetCategoryResponse.class);
        // 프롬프트에 같은 순서로 들어가야 해서 LinkedHashMap으로 읽음
        JavaType ingredientUnits = jsonMapper.getTypeFactory()
                .constructMapType(LinkedHashMap.class, String.class, BaseUnit.class);
        JavaType productDetail = jsonMapper.getTypeFactory().constructType(ProductDetail.class);
        // 기본은 Redis 쓰기를 비동기로 해서, 조회가 캐시에 넣는 중에 관리자 수정의 삭제가 먼저 끝나면 옛 값이 다시 남을 수 있음
        // 캐시가 빈 순간 요청이 몰려도 DB는 한 번만 읽게 sync = true인 캐시는 Redis 락(캐시이름~lock)을 잡고 채움. 서버가 여러 대여도 막힘
        // 락이 캐시 단위라 키가 하나인 카테고리·재료 단위에만 sync를 씀. 상품 상세에 쓰면 다른 상품 조회까지 기다리게 됨
        RedisCacheWriter cacheWriter = RedisCacheWriter.create(redisConnectionFactory, configurer -> configurer.immediateWrites()
                .enableLocking(lock -> lock.lockTimeout(RedisCacheWriter.TtlFunction.just(LOCK_TIMEOUT))));
        return RedisCacheManager.builder(cacheWriter)
                .withCacheConfiguration(CATEGORIES, cacheConfiguration(jsonMapper, categories))
                .withCacheConfiguration(INGREDIENT_UNITS, cacheConfiguration(jsonMapper, ingredientUnits))
                .withCacheConfiguration(PRODUCT_DETAIL, cacheConfiguration(jsonMapper, productDetail))
                .build();
    }

    // Redis가 안 돼도 조회는 DB로 계속 되게 캐시 오류는 로그만 남김
    // 삭제 실패는 옛 값이 TTL 동안 남을 수 있어서 error로 남김. 주문은 DB 가격으로 다시 확인해서 결제 금액은 안 틀어짐
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {

            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("캐시 조회 실패 cache={}, error={}", cache.getName(), exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("캐시 저장 실패 cache={}, error={}", cache.getName(), exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.error("캐시 삭제 실패 cache={}, key={}", cache.getName(), key, exception);
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.error("캐시 비우기 실패 cache={}", cache.getName(), exception);
            }
        };
    }

    private RedisCacheConfiguration cacheConfiguration(JsonMapper jsonMapper, JavaType type) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(TTL)
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(jsonMapper, type)));
    }
}