package com.server.zelo.domain.product.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.server.zelo.common.api.PageResponse;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.product.dto.request.ProductSort;
import com.server.zelo.domain.product.dto.request.SearchProductCondition;
import com.server.zelo.domain.product.dto.response.GetProductResponse;
import com.server.zelo.domain.product.dto.response.GetProductSummaryResponse;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.ProductStatus;
import com.server.zelo.domain.product.service.ProductCommandService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    @Mock
    private ProductCommandService productCommandService;

    @InjectMocks
    private ProductController productController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(productController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 조건_없이_검색하면_기본값으로_찾는다() throws Exception {
        // given
        given(productCommandService.searchProducts(any())).willReturn(new PageResponse<>(List.of(
                new GetProductSummaryResponse("SID-0008", "브랜드 A", "백김치 1kg", 8900L, "1kg", "김치·젓갈", 7)),
                0, 20, 1, 1));

        // when & then
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].productCode").value("SID-0008"))
                .andExpect(jsonPath("$.data.content[0].availableQuantity").value(7))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        then(productCommandService).should()
                .searchProducts(new SearchProductCondition("", null, false, ProductSort.SALES, 0, 20));
    }

    @Test
    void 검색_조건을_그대로_넘긴다() throws Exception {
        // given
        given(productCommandService.searchProducts(any())).willReturn(new PageResponse<>(List.of(), 1, 10, 0, 0));

        // when & then
        mockMvc.perform(get("/api/products")
                        .param("keyword", "김치")
                        .param("categoryId", "3")
                        .param("inStock", "true")
                        .param("sort", "PRICE_ASC")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk());
        then(productCommandService).should()
                .searchProducts(new SearchProductCondition("김치", 3L, true, ProductSort.PRICE_ASC, 1, 10));
    }

    @ParameterizedTest
    @CsvSource({"page, -1", "size, 0", "size, 101", "categoryId, 0", "sort, CHEAP"})
    void 검색_조건이_잘못되면_400을_반환한다(String name, String value) throws Exception {
        // when & then
        mockMvc.perform(get("/api/products").param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(productCommandService);
    }

    @Test
    void 상품_코드로_상세를_조회한다() throws Exception {
        // given
        given(productCommandService.getProduct("SID-0008")).willReturn(new GetProductResponse(
                "SID-0008", "브랜드 A", "백김치 1kg", null, 8900L, "1kg", "김치·젓갈",
                List.of(Keyword.NOT_SPICY), 7, ProductStatus.ON_SALE));

        // when & then
        mockMvc.perform(get("/api/products/SID-0008"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("백김치 1kg"))
                .andExpect(jsonPath("$.data.keywords[0]").value("NOT_SPICY"))
                .andExpect(jsonPath("$.data.status").value("ON_SALE"));
    }

    @Test
    void 없는_상품이면_404를_반환한다() throws Exception {
        // given
        given(productCommandService.getProduct("NOPE")).willThrow(new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        // when & then
        mockMvc.perform(get("/api/products/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.PRODUCT_NOT_FOUND.getCode()));
    }
}