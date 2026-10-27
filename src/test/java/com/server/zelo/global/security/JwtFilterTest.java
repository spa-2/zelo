package com.server.zelo.global.security;

import com.server.zelo.domain.user.entity.UserRole;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.common.auth.JwtUtil;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class JwtFilterTest {

    @Mock
    private JwtUtil jwtUtil;

    @InjectMocks
    private JwtFilter jwtFilter;

    private MockFilterChain filterChain;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        filterChain = new MockFilterChain();
    }

    // 테스트끼리 인증 정보가 섞이지 않게 비움
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void Authorization_헤더가_없으면_UNAUTHORIZED가_설정된다() throws ServletException, IOException {
        // given

        // when
        jwtFilter.doFilter(request, response, filterChain);

        // then
        assertThat(request.getAttribute("exception")).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(filterChain.getRequest()).isNotNull();
    }

    @Test
    void Authorization_헤더가_빈_문자열이면_UNAUTHORIZED가_설정된다() throws ServletException, IOException {
        // given
        request.addHeader("Authorization", "");

        // when
        jwtFilter.doFilter(request, response, filterChain);

        // then
        assertThat(request.getAttribute("exception")).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(filterChain.getRequest()).isNotNull();
    }

    @Test
    void Bearer_형식이_아니면_INVALID_TOKEN이_설정된다() throws ServletException, IOException {
        // given
        request.addHeader("Authorization", "abc.def.ghi");
        given(jwtUtil.substringToken("abc.def.ghi"))
                .willThrow(new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        // when
        jwtFilter.doFilter(request, response, filterChain);

        // then
        assertThat(request.getAttribute("exception")).isEqualTo(ErrorCode.INVALID_TOKEN);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(filterChain.getRequest()).isNotNull();
    }

    @Test
    void 검증에_실패한_토큰이면_INVALID_TOKEN이_설정된다() throws ServletException, IOException {
        // given
        request.addHeader("Authorization", "Bearer token");
        given(jwtUtil.substringToken("Bearer token")).willReturn("token");
        given(jwtUtil.validateToken("token")).willReturn(false);

        // when
        jwtFilter.doFilter(request, response, filterChain);

        // then
        assertThat(request.getAttribute("exception")).isEqualTo(ErrorCode.INVALID_TOKEN);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(filterChain.getRequest()).isNotNull();
    }

    @Test
    void 리프레시_토큰으로_호출하면_INVALID_TOKEN이_설정된다() throws ServletException, IOException {
        // given
        request.addHeader("Authorization", "Bearer refreshToken");
        given(jwtUtil.substringToken("Bearer refreshToken")).willReturn("refreshToken");
        given(jwtUtil.validateToken("refreshToken")).willReturn(true);
        given(jwtUtil.isAccessToken("refreshToken")).willReturn(false);

        // when
        jwtFilter.doFilter(request, response, filterChain);

        // then
        assertThat(request.getAttribute("exception")).isEqualTo(ErrorCode.INVALID_TOKEN);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(filterChain.getRequest()).isNotNull();
    }

    @Test
    void 정상_액세스_토큰이면_인증_정보가_설정된다() throws ServletException, IOException {
        // given
        request.addHeader("Authorization", "Bearer accessToken");
        given(jwtUtil.substringToken("Bearer accessToken")).willReturn("accessToken");
        given(jwtUtil.validateToken("accessToken")).willReturn(true);
        given(jwtUtil.isAccessToken("accessToken")).willReturn(true);
        given(jwtUtil.getUserId("accessToken")).willReturn(1L);
        given(jwtUtil.getUserRole("accessToken")).willReturn(UserRole.BASIC);

        // when
        jwtFilter.doFilter(request, response, filterChain);

        // then
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isEqualTo(AuthUser.of(1L, UserRole.BASIC));
        assertThat(request.getAttribute("exception")).isNull();
        assertThat(filterChain.getRequest()).isNotNull();
    }

    @Test
    void 토큰의_role이_ADMIN이면_ROLE_ADMIN_권한이_설정된다() throws ServletException, IOException {
        // given
        request.addHeader("Authorization", "Bearer accessToken");
        given(jwtUtil.substringToken("Bearer accessToken")).willReturn("accessToken");
        given(jwtUtil.validateToken("accessToken")).willReturn(true);
        given(jwtUtil.isAccessToken("accessToken")).willReturn(true);
        given(jwtUtil.getUserId("accessToken")).willReturn(1L);
        given(jwtUtil.getUserRole("accessToken")).willReturn(UserRole.ADMIN);

        // when
        jwtFilter.doFilter(request, response, filterChain);

        // then
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication.getPrincipal()).isEqualTo(AuthUser.of(1L, UserRole.ADMIN));
        assertThat(authentication.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
    }
}
