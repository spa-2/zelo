package com.server.zelo.domain.user.controller;

import com.server.zelo.common.auth.AuthArgumentResolver;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.user.dto.response.CreateUserAddressResponse;
import com.server.zelo.domain.user.dto.response.GetUserAddressResponse;
import com.server.zelo.domain.user.dto.response.UpdateUserAddressResponse;
import com.server.zelo.domain.user.service.UserAddressService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
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

    @Mock private UserAddressService userAddressService;

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

    @Test
    void 배송지_목록을_요청하면_200과_목록을_반환한다() throws Exception {
        // given
        authenticate(1L);
        given(userAddressService.getUserAddresses(1L)).willReturn(List.of(
                new GetUserAddressResponse(1L, "집", "홍길동", "010-1234-5678", "12345", "서울시 강남구", true),
                new GetUserAddressResponse(2L, "회사", "홍길동", "010-1234-5678", "54321", "서울시 중구", false)));

        // when // then
        mockMvc.perform(get("/api/users/me/addresses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].place").value("집"))
                .andExpect(jsonPath("$.data[0].isDefault").value(true));
    }

    @Test
    void 인증_정보_없이_목록을_요청하면_401을_반환한다() throws Exception {
        // when // then
        mockMvc.perform(get("/api/users/me/addresses"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
        then(userAddressService).should(never()).getUserAddresses(anyLong());
    }

    @Test
    void 배송지를_수정하면_200과_수정한_배송지를_반환한다() throws Exception {
        // given
        authenticate(1L);
        given(userFacade.updateUserAddress(eq(1L), eq(2L), any())).willReturn(
                new UpdateUserAddressResponse(2L, "회사", "김철수", "010-9876-5432", "54321", "서울시 중구", true));

        // when // then
        mockMvc.perform(put("/api/users/me/addresses/{addressId}", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.addressId").value(2))
                .andExpect(jsonPath("$.data.place").value("회사"));
    }

    @Test
    void 수정할_때_필수값이_없으면_400을_반환하고_수정하지_않는다() throws Exception {
        // given
        authenticate(1L);
        String body = """
                {"place":"","recipientName":"김철수","recipientPhone":"010-9876-5432",
                 "zipCode":"54321","address":"서울시 중구","isDefault":true}""";

        // when // then
        mockMvc.perform(put("/api/users/me/addresses/{addressId}", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()));
        then(userFacade).should(never()).updateUserAddress(anyLong(), anyLong(), any());
    }

    private void authenticate(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(AuthUser.of(userId), null, AuthorityUtils.NO_AUTHORITIES));
    }
}