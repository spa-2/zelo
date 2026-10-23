package com.server.zelo.global.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.global.seed.ReviewSeedLoader.ReviewSeed;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

// 시드 파일을 고칠 때 실수를 앱을 띄우기 전에 잡는다: 상품 시드와 코드가 맞는지, 엔티티 검증을 통과할 값인지
class ReviewSeedDataTest {

    private final List<ReviewSeed> seeds = readSeeds();

    @Test
    void 리뷰의_상품_코드는_모두_상품_시드에_있다() {
        // given
        Set<String> productCodes = SeedDataLoader.readRows("seed/products.csv").stream()
                .map(row -> row[0])
                .collect(Collectors.toSet());

        // then
        assertThat(seeds).extracting(ReviewSeed::productCode).allSatisfy(code -> assertThat(productCodes).contains(code));
    }

    @Test
    void 평점은_1부터_5_사이고_내용은_1000자_이내다() {
        // then
        assertThat(seeds).allSatisfy(seed -> {
            assertThat(seed.rating()).isBetween(1, 5);
            assertThat(seed.content()).isNotBlank().hasSizeLessThanOrEqualTo(1000);
            assertThat(seed.keywords()).isNotNull().doesNotHaveDuplicates();
        });
    }

    @Test
    void 시연용_김치_리뷰는_요약_기준인_5건_이상이고_조건_키워드가_달려_있다() {
        // then: 백김치(맵지 않음)와 포기김치(얼큰함)가 "초등학생"·"매콤하게" 시연의 리뷰 근거가 된다
        assertThat(seeds).filteredOn(seed -> seed.productCode().equals("SID-0008")).hasSizeGreaterThanOrEqualTo(5);
        assertThat(seeds).filteredOn(seed -> seed.productCode().equals("SID-0006")).hasSizeGreaterThanOrEqualTo(5);
    }

    private static List<ReviewSeed> readSeeds() {
        try {
            String content = new ClassPathResource("seed/reviews.json").getContentAsString(StandardCharsets.UTF_8);
            return JsonMapper.builder().build().readValue(content, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
