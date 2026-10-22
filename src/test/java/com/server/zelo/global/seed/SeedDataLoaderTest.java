package com.server.zelo.global.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.server.zelo.domain.inventory.entity.Inventory;
import com.server.zelo.domain.inventory.repository.InventoryRepository;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.ProductKeyword;
import com.server.zelo.domain.product.repository.CategoryRepository;
import com.server.zelo.domain.product.repository.IngredientRepository;
import com.server.zelo.domain.product.repository.ProductKeywordRepository;
import com.server.zelo.domain.product.repository.ProductRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeedDataLoaderTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private IngredientRepository ingredientRepository;

    @Mock
    private ProductKeywordRepository productKeywordRepository;

    @InjectMocks
    private SeedDataLoader seedDataLoader;

    @Test
    void 카테고리가_이미_있으면_아무것도_넣지_않는다() {
        // given
        given(categoryRepository.count()).willReturn(3L);

        // when
        seedDataLoader.run(null);

        // then
        then(categoryRepository).should(never()).save(any());
        then(productRepository).should(never()).saveAll(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 비어_있으면_CSV를_모두_저장한다() {
        // given
        List<Category> savedCategories = new ArrayList<>();
        given(categoryRepository.count()).willReturn(0L);
        given(categoryRepository.save(any(Category.class))).willAnswer(invocation -> {
            savedCategories.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        ArgumentCaptor<List<Inventory>> inventories = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<ProductKeyword>> keywords = ArgumentCaptor.forClass(List.class);

        // when
        seedDataLoader.run(null);

        // then
        then(inventoryRepository).should().saveAll(inventories.capture());
        then(productKeywordRepository).should().saveAll(keywords.capture());
        assertThat(savedCategories).hasSize(SeedDataLoader.readRows("seed/categories.csv").size());
        assertThat(inventories.getValue()).hasSize(SeedDataLoader.readRows("seed/products.csv").size());

        // 주류 아래 소분류는 대분류를 따라 AI 주문 불가
        assertThat(savedCategories).filteredOn(category -> category.getName().equals("맥주"))
                .singleElement().satisfies(category -> assertThat(category.isAgentOrderable()).isFalse());
        // 품절 대체 시나리오용으로 국산 대파는 재고 0
        assertThat(inventories.getValue()).filteredOn(inventory -> inventory.getProduct().getName().equals("국산 대파 1단"))
                .singleElement().satisfies(inventory -> assertThat(inventory.getAvailableQuantity()).isZero());
        assertThat(keywords.getValue()).filteredOn(keyword -> keyword.getProduct().getName().equals("백김치 1kg"))
                .extracting(ProductKeyword::getKeyword).containsExactly(Keyword.NOT_SPICY);
        // 같은 이름이어도 브랜드로 구분되는 상품
        assertThat(inventories.getValue()).extracting(Inventory::getProduct)
                .filteredOn(product -> product.getName().equals("포기김치 1kg"))
                .extracting(product -> product.getBrand()).containsExactlyInAnyOrder("브랜드 G", "브랜드 H");
    }

    @Test
    void CSV_이름이_서로_겹치지_않는다() {
        // given
        List<String[]> categories = SeedDataLoader.readRows("seed/categories.csv");
        List<String[]> ingredients = SeedDataLoader.readRows("seed/ingredients.csv");
        List<String[]> products = SeedDataLoader.readRows("seed/products.csv");

        // when & then (DB에 유니크 제약이 있어서 겹치면 앱 시작이 실패함)
        assertThat(categories).extracting(row -> row[1]).doesNotHaveDuplicates();
        assertThat(ingredients).extracting(row -> row[1]).doesNotHaveDuplicates();
        assertThat(products).extracting(row -> row[0]).doesNotHaveDuplicates();
        // 같은 재료·이름·브랜드 상품이 두 번 들어가면 안 됨
        assertThat(products).extracting(row -> row[1] + "/" + row[2] + "/" + row[3]).doesNotHaveDuplicates();
    }

    @Test
    void 상품_코드는_대분류_약자로_시작한다() {
        // given
        Map<String, String> parentByCategory = SeedDataLoader.readRows("seed/categories.csv").stream()
                .collect(Collectors.toMap(row -> row[1], row -> row[0]));
        Map<String, String> categoryByIngredient = SeedDataLoader.readRows("seed/ingredients.csv").stream()
                .collect(Collectors.toMap(row -> row[1], row -> row[0]));
        Map<String, String> prefixByTopCategory = Map.ofEntries(
                Map.entry("채소", "VEG"), Map.entry("과일·견과·쌀", "FRU"), Map.entry("수산·해산·건어물", "SEA"),
                Map.entry("정육·가공육·달걀", "MEA"), Map.entry("국·반찬·메인요리", "SID"),
                Map.entry("간편식·밀키트·샐러드", "RTE"), Map.entry("면·양념·오일", "SAU"), Map.entry("유제품", "DAI"),
                Map.entry("생수·음료·커피", "BEV"), Map.entry("간식·과자·베이커리", "SNK"), Map.entry("주류", "ALC"));

        // when & then
        assertThat(SeedDataLoader.readRows("seed/products.csv")).allSatisfy(row -> {
            String topCategory = parentByCategory.get(categoryByIngredient.get(row[1]));
            assertThat(row[0]).matches(prefixByTopCategory.get(topCategory) + "-[0-9]{4}");
        });
    }

    @Test
    void 모든_재료에_상품이_하나_이상_있다() {
        // given
        List<String> ingredientNames = SeedDataLoader.readRows("seed/ingredients.csv").stream()
                .map(row -> row[1]).toList();
        List<String> productIngredients = SeedDataLoader.readRows("seed/products.csv").stream()
                .map(row -> row[1]).toList();

        // when & then
        assertThat(productIngredients).containsAll(ingredientNames);
    }

    @Test
    void AI_프롬프트_재료_이름과_단위가_시드와_같다() {
        // given: 유림님 MenuPlanPrompt 임시 재료 목록 (#81). 이름이나 단위가 다르면 견적에서 수량 계산이 안 됨
        Map<String, BaseUnit> promptIngredients = Map.ofEntries(
                Map.entry("김치", BaseUnit.G), Map.entry("밥", BaseUnit.G), Map.entry("계란", BaseUnit.EA),
                Map.entry("대파", BaseUnit.BUNCH), Map.entry("양파", BaseUnit.EA), Map.entry("햄", BaseUnit.G),
                Map.entry("돼지고기", BaseUnit.G), Map.entry("두부", BaseUnit.EA), Map.entry("식용유", BaseUnit.ML),
                Map.entry("간장", BaseUnit.ML), Map.entry("참기름", BaseUnit.ML), Map.entry("고추장", BaseUnit.G),
                Map.entry("소주", BaseUnit.ML));

        // when
        Map<String, BaseUnit> seedIngredients = SeedDataLoader.readRows("seed/ingredients.csv").stream()
                .collect(Collectors.toMap(row -> row[1], row -> BaseUnit.valueOf(row[2]), (a, b) -> a));

        // then
        assertThat(seedIngredients).containsAllEntriesOf(promptIngredients);
    }

    @Test
    void 재료는_모두_소분류에_들어간다() {
        // given
        Map<String, String> parentByCategory = SeedDataLoader.readRows("seed/categories.csv").stream()
                .collect(Collectors.toMap(row -> row[1], row -> row[0]));

        // when & then
        assertThat(SeedDataLoader.readRows("seed/ingredients.csv"))
                .allSatisfy(row -> assertThat(parentByCategory.get(row[0])).isNotBlank());
    }

    @Test
    void 시연에_꼭_필요한_상품이_들어있다() {
        // given: 팀에서 정한 최소 시연 데이터 (재료별 최소 상품 수)
        Map<String, Integer> required = Map.ofEntries(
                Map.entry("김치", 3), Map.entry("밥", 2), Map.entry("쌀", 1), Map.entry("계란", 2),
                Map.entry("햄", 1), Map.entry("돼지고기", 1), Map.entry("대파", 2), Map.entry("양파", 1),
                Map.entry("애호박", 1), Map.entry("감자", 1), Map.entry("청양고추", 1), Map.entry("두부", 2),
                Map.entry("식용유", 1), Map.entry("참기름", 1), Map.entry("간장", 1), Map.entry("고추장", 1),
                Map.entry("된장", 1), Map.entry("소주", 1));

        // when
        Map<String, Long> productCount = SeedDataLoader.readRows("seed/products.csv").stream()
                .collect(Collectors.groupingBy(row -> row[1], Collectors.counting()));

        // then
        required.forEach((ingredient, min) -> assertThat(productCount.getOrDefault(ingredient, 0L))
                .as(ingredient).isGreaterThanOrEqualTo(min));
        // 두부는 국산/일반 쌍, 계란은 일반/무항생제 쌍
        assertThat(SeedDataLoader.readRows("seed/products.csv"))
                .anySatisfy(row -> assertThat(row[1] + row[8]).isEqualTo("두부DOMESTIC;VEGETARIAN"))
                .anySatisfy(row -> assertThat(row[3]).isEqualTo("무항생제 계란 10구"));
    }
}
