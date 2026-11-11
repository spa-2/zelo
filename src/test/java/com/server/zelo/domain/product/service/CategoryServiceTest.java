package com.server.zelo.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.server.zelo.domain.product.dto.response.GetCategoryResponse;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.repository.CategoryRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private CategoryService categoryService;

    @Test
    void 대분류_아래에_소분류를_묶어서_반환한다() {
        // given
        Category vegetable = category(Category.of("채소", true), 1L);
        Category liquor = category(Category.of("주류", false), 2L);
        Category onion = category(Category.of(vegetable, "양파·대파·마늘·배추"), 3L);
        Category beer = category(Category.of(liquor, "맥주"), 4L);
        Category mushroom = category(Category.of(vegetable, "콩나물·버섯"), 5L);
        given(categoryRepository.findAllByOrderByIdAsc()).willReturn(List.of(vegetable, liquor, onion, beer, mushroom));

        // when
        List<GetCategoryResponse> result = categoryService.getCategories();

        // then
        assertThat(result).extracting(GetCategoryResponse::name).containsExactly("채소", "주류");
        assertThat(result.get(0).children()).extracting(GetCategoryResponse.SubCategory::name)
                .containsExactly("양파·대파·마늘·배추", "콩나물·버섯");
        assertThat(result.get(1).children()).extracting(GetCategoryResponse.SubCategory::categoryId)
                .containsExactly(4L);
    }

    @Test
    void 소분류가_없는_대분류는_빈_목록을_갖는다() {
        // given
        Category vegetable = category(Category.of("채소", true), 1L);
        given(categoryRepository.findAllByOrderByIdAsc()).willReturn(List.of(vegetable));

        // when
        List<GetCategoryResponse> result = categoryService.getCategories();

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).children()).isEmpty();
    }

    @Test
    void 카테고리가_없으면_빈_목록을_반환한다() {
        // given
        given(categoryRepository.findAllByOrderByIdAsc()).willReturn(List.of());

        // when
        List<GetCategoryResponse> result = categoryService.getCategories();

        // then
        assertThat(result).isEmpty();
    }

    private Category category(Category category, Long id) {
        ReflectionTestUtils.setField(category, "id", id);
        return category;
    }
}