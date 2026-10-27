package com.server.zelo.domain.product.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.product.dto.request.UpdateProductRequest;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AdminProductControllerTest {

    @Mock
    private ProductService productService;

    @InjectMocks
    private AdminProductController adminProductController;

    private MockMvc mockMvc;

    private final Ingredient kimchi = Ingredient.of(Category.of(Category.of("국·반찬", true), "김치·젓갈"), "김치", BaseUnit.G);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(adminProductController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 상품을_등록하면_201을_반환한다() throws Exception {
        // given
        given(productService.createProduct(any())).willReturn(
                Product.of("P-NEW", kimchi, "브랜드 A", "백김치 1kg", null, 8900L, 1000, "1kg"));

        // when & then
        mockMvc.perform(post("/api/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productCode":"P-NEW","ingredientId":3,"brand":"브랜드 A","name":"백김치 1kg",
                                "price":8900,"contentAmount":1000,"unitLabel":"1kg"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.productCode").value("P-NEW"))
                .andExpect(jsonPath("$.data.categoryName").value("김치·젓갈"))
                .andExpect(jsonPath("$.data.status").value("ON_SALE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"ingredientId\":3,\"brand\":\"A\",\"name\":\"김치\",\"price\":1,\"contentAmount\":1,\"unitLabel\":\"1kg\"}",
            "{\"productCode\":\"P\",\"ingredientId\":3,\"brand\":\"A\",\"name\":\"김치\",\"price\":-1,\"contentAmount\":1,\"unitLabel\":\"1kg\"}",
            "{\"productCode\":\"P\",\"ingredientId\":3,\"brand\":\"A\",\"name\":\"김치\",\"price\":1,\"contentAmount\":0,\"unitLabel\":\"1kg\"}",
            "{\"productCode\":\"A/B\",\"ingredientId\":3,\"brand\":\"A\",\"name\":\"김치\",\"price\":1,\"contentAmount\":1,\"unitLabel\":\"1kg\"}"
    })
    void 등록_요청이_잘못되면_400을_반환한다(String body) throws Exception {
        // when & then
        mockMvc.perform(post("/api/admin/products").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(productService);
    }

    @Test
    void 가격과_설명을_바꾼다() throws Exception {
        // given
        Product product = Product.of("P-1", kimchi, "브랜드 A", "백김치 1kg", "새 설명", 9900L, 1000, "1kg");
        given(productService.updateProduct(eq("P-1"), eq(new UpdateProductRequest(9900L, "새 설명")))).willReturn(product);

        // when & then
        mockMvc.perform(patch("/api/admin/products/P-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":9900,\"description\":\"새 설명\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(9900));
    }

    @Test
    void 판매중지하면_상태를_돌려준다() throws Exception {
        // given
        Product product = Product.of("P-1", kimchi, "브랜드 A", "백김치 1kg", null, 8900L, 1000, "1kg");
        product.stopSale();
        given(productService.stopSale("P-1")).willReturn(product);

        // when & then
        mockMvc.perform(patch("/api/admin/products/P-1/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("STOP_SALE"));
    }

    @Test
    void 이미_판매중지된_상품이면_409를_반환한다() throws Exception {
        // given
        given(productService.stopSale("P-1")).willThrow(new BusinessException(ErrorCode.INVALID_PRODUCT_STATUS));

        // when & then
        mockMvc.perform(patch("/api/admin/products/P-1/stop"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_008"));
    }
}