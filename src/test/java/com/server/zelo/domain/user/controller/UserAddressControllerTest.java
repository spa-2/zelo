package com.server.zelo.domain.user.controller;

import com.server.zelo.common.auth.AuthArgumentResolver;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.user.dto.response.CreateUserAddressResponse;
import com.server.zelo.domain.user.service.UserFacade;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserAddressControllerTest {

    private static final String BODY = """
            {"place":"회사","recipientName":"홍길동","recipientPhone":"010-1234-5678",
             "zipCode":"12345","address":"서울시 강남구","isDefault":true}""";

    @InjectMocks
    private UserAddressController userAddressController;

    @Mock
    private UserFacade userFacade;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(userAddressController)
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
    void 배송지를_등록하면_201과_등록한_배송지를_반환한다() throws Exception {
        // given
        authenticate(1L);
        given(userFacade.createUserAddress(eq(1L), any())).willReturn(
                new CreateUserAddressResponse(2L, "회사", "홍길동", "010-1234-5678", "12345", "서울시 강남구", true));

        // when // then
        mockMvc.perform(post("/api/users/me/addresses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.addressId").value(2))
                .andExpect(jsonPath("$.data.isDefault").value(true));
    }

    @Test
    void 기본_배송지_여부가_없으면_400을_반환하고_등록하지_않는다() throws Exception {
        // given
        authenticate(1L);
        String body = """
                {"place":"회사","recipientName":"홍길동","recipientPhone":"010-1234-5678",
                 "zipCode":"12345","address":"서울시 강남구"}""";

        // when // then
        mockMvc.perform(post("/api/users/me/addresses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()));
        then(userFacade).should(never()).createUserAddress(anyLong(), any());
    }

    @Test
    void 인증_정보가_없으면_401을_반환하고_등록하지_않는다() throws Exception {
        // when // then
        mockMvc.perform(post("/api/users/me/addresses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
        then(userFacade).should(never()).createUserAddress(anyLong(), any());
    }

    private void authenticate(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(AuthUser.of(userId), null, AuthorityUtils.NO_AUTHORITIES));
    }
}