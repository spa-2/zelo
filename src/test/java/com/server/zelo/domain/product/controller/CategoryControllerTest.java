package com.server.zelo.domain.product.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.product.dto.response.GetCategoryResponse;
import com.server.zelo.domain.product.dto.response.GetCategoryResponse.SubCategory;
import com.server.zelo.domain.product.service.CategoryService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class CategoryControllerTest {

    @Mock
    private CategoryService categoryService;

    @InjectMocks
    private CategoryController categoryController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(categoryController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 로그인_없이_요청해도_200과_카테고리_트리를_반환한다() throws Exception {
        // given
        given(categoryService.getCategories()).willReturn(List.of(
                new GetCategoryResponse(1L, "채소", List.of(new SubCategory(3L, "양파·대파·마늘·배추"))),
                new GetCategoryResponse(2L, "주류", List.of())));

        // when & then
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("채소"))
                .andExpect(jsonPath("$.data[0].children[0].categoryId").value(3))
                .andExpect(jsonPath("$.data[0].children[0].name").value("양파·대파·마늘·배추"))
                .andExpect(jsonPath("$.data[1].children").isEmpty());
    }

    @Test
    void 카테고리가_없으면_빈_배열을_반환한다() throws Exception {
        // given
        given(categoryService.getCategories()).willReturn(List.of());

        // when & then
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }
}