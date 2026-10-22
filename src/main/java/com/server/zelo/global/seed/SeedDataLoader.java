package com.server.zelo.global.seed;

import com.server.zelo.domain.inventory.entity.Inventory;
import com.server.zelo.domain.inventory.repository.InventoryRepository;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.entity.ProductKeyword;
import com.server.zelo.domain.product.repository.CategoryRepository;
import com.server.zelo.domain.product.repository.IngredientRepository;
import com.server.zelo.domain.product.repository.ProductKeywordRepository;
import com.server.zelo.domain.product.repository.ProductRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 로컬 시연용 데이터. ZELO_SEED_ENABLED=true 로 켰을 때만 돌고, 카테고리가 이미 있으면 건너뜀
// 여러 도메인(상품, 재고) 데이터를 한 번에 넣는 거라 서비스 대신 리포지토리를 직접 씀
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "zelo.seed.enabled", havingValue = "true")
public class SeedDataLoader implements ApplicationRunner {

    private static final String CATEGORIES_CSV = "seed/categories.csv";
    private static final String INGREDIENTS_CSV = "seed/ingredients.csv";
    private static final String PRODUCTS_CSV = "seed/products.csv";

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final InventoryRepository inventoryRepository;
    private final IngredientRepository ingredientRepository;
    private final ProductKeywordRepository productKeywordRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (categoryRepository.count() > 0) {
            log.info("시드 데이터가 이미 있어서 건너뜀");
            return;
        }
        Map<String, Category> categories = saveCategories();
        Map<String, Ingredient> ingredients = saveIngredients(categories);
        int productCount = saveProducts(ingredients);
        log.info("시드 데이터 저장 완료 카테고리={}, 재료={}, 상품={}", categories.size(), ingredients.size(), productCount);
    }

    // 대분류가 소분류보다 위에 있어야 해서 파일 순서대로 저장함
    private Map<String, Category> saveCategories() {
        Map<String, Category> categories = new HashMap<>();
        for (String[] row : readRows(CATEGORIES_CSV)) {
            String parentName = row[0];
            String name = row[1];
            Category category = parentName.isBlank()
                    ? Category.of(name, Boolean.parseBoolean(row[2]))
                    : Category.of(find(categories, parentName, CATEGORIES_CSV), name);
            categories.put(name, categoryRepository.save(category));
        }
        return categories;
    }

    private Map<String, Ingredient> saveIngredients(Map<String, Category> categories) {
        Map<String, Ingredient> ingredients = new HashMap<>();
        for (String[] row : readRows(INGREDIENTS_CSV)) {
            Category category = find(categories, row[0], INGREDIENTS_CSV);
            // 상품은 재료의 카테고리를 따라가서 재료는 소분류에만 붙임
            if (category.getParent() == null) {
                throw new IllegalStateException(INGREDIENTS_CSV + " 재료는 소분류에 넣어야 함: " + row[1]);
            }
            ingredients.put(row[1], Ingredient.of(category, row[1], BaseUnit.valueOf(row[2])));
        }
        ingredientRepository.saveAll(ingredients.values());
        return ingredients;
    }

    private int saveProducts(Map<String, Ingredient> ingredients) {
        List<Product> products = new ArrayList<>();
        List<Inventory> inventories = new ArrayList<>();
        List<ProductKeyword> productKeywords = new ArrayList<>();
        for (String[] row : readRows(PRODUCTS_CSV)) {
            // productCode,ingredient,brand,name,price,contentAmount,unitLabel,stock,keywords
            Product product = Product.of(row[0], find(ingredients, row[1], PRODUCTS_CSV), row[2], row[3], null,
                    Long.parseLong(row[4]), Integer.parseInt(row[5]), row[6]);
            products.add(product);
            inventories.add(Inventory.of(product, Integer.parseInt(row[7])));
            Arrays.stream(row[8].split(";"))
                    .filter(keyword -> !keyword.isBlank())
                    .map(keyword -> ProductKeyword.of(product, Keyword.valueOf(keyword)))
                    .forEach(productKeywords::add);
        }
        productRepository.saveAll(products);
        inventoryRepository.saveAll(inventories);
        productKeywordRepository.saveAll(productKeywords);
        return products.size();
    }

    // CSV에 오타가 있으면 어디가 틀렸는지 바로 보이게 함
    private <T> T find(Map<String, T> saved, String name, String file) {
        T value = saved.get(name);
        if (value == null) {
            throw new IllegalStateException(file + "에서 찾을 수 없는 이름: " + name);
        }
        return value;
    }

    // 첫 줄은 헤더. 값에 쉼표는 안 씀
    static List<String[]> readRows(String path) {
        try {
            String content = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
            return content.lines()
                    .skip(1)
                    .filter(line -> !line.isBlank())
                    .map(line -> line.split(",", -1))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
