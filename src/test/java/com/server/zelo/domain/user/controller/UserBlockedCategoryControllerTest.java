package com.server.zelo.domain.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.server.zelo.common.auth.AuthArgumentResolver;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.user.dto.response.CreateBlockedCategoryResponse;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.entity.UserBlockedCategory;
import com.server.zelo.domain.user.entity.UserRole;
import com.server.zelo.domain.user.service.UserBlockedCategoryCommandService;
import com.server.zelo.domain.user.service.UserBlockedCategoryService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class UserBlockedCategoryControllerTest {

    @Mock
    private UserBlockedCategoryService userBlockedCategoryService;

    @Mock
    private UserBlockedCategoryCommandService userBlockedCategoryCommandService;

    @InjectMocks
    private UserBlockedCategoryController userBlockedCategoryController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(userBlockedCategoryController)
                .setCustomArgumentResolvers(new AuthArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // 테스트끼리 인증 정보가 섞이지 않게 비움
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 막은_카테고리_목록을_준다() throws Exception {
        // given
        authenticate(1L);
        User user = User.of("user@zelo.com", "encoded", "사용자", "010-1234-5678");
        Category nuts = Category.of("견과류", true);
        ReflectionTestUtils.setField(nuts, "id", 7L);
        given(userBlockedCategoryService.findBlockedCategories(1L)).willReturn(List.of(UserBlockedCategory.of(user, nuts)));

        // when & then
        mockMvc.perform(get("/api/users/me/blocked-categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].categoryId").value(7))
                .andExpect(jsonPath("$.data[0].name").value("견과류"));
    }

    @Test
    void 카테고리를_막으면_201을_준다() throws Exception {
        // given
        authenticate(1L);
        given(userBlockedCategoryCommandService.blockCategory(1L, 7L)).willReturn(new CreateBlockedCategoryResponse(7L, "견과류"));

        // when & then
        mockMvc.perform(post("/api/users/me/blocked-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":7}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.categoryId").value(7));
    }

    @Test
    void 카테고리_ID가_없으면_400이고_막지_않는다() throws Exception {
        // given
        authenticate(1L);

        // when & then
        mockMvc.perform(post("/api/users/me/blocked-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        then(userBlockedCategoryCommandService).should(never()).blockCategory(anyLong(), any());
    }

    @Test
    void 이미_막은_카테고리면_409를_준다() throws Exception {
        // given
        authenticate(1L);
        given(userBlockedCategoryCommandService.blockCategory(1L, 7L))
                .willThrow(new BusinessException(ErrorCode.ALREADY_BLOCKED_CATEGORY));

        // when & then
        mockMvc.perform(post("/api/users/me/blocked-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":7}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_011"));
    }

    @Test
    void 막은_카테고리를_풀면_204를_준다() throws Exception {
        // given
        authenticate(1L);

        // when & then
        mockMvc.perform(delete("/api/users/me/blocked-categories/7"))
                .andExpect(status().isNoContent());
        then(userBlockedCategoryService).should().unblockCategory(1L, 7L);
    }

    private void authenticate(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(AuthUser.of(userId, UserRole.BASIC), null, AuthorityUtils.NO_AUTHORITIES));
    }
}