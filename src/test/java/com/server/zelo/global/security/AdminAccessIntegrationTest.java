package com.server.zelo.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.server.zelo.common.auth.JwtUtil;
import com.server.zelo.domain.user.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

// /api/admin/** 의 RBAC를 확인한다. 실제 admin API가 없어서 인가에서 막히는 경우(401/403)만 검증한다
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminAccessIntegrationTest {

    private static final String ADMIN_URL = "/api/admin/test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(get(ADMIN_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void BASIC_사용자는_403이다() throws Exception {
        String token = jwtUtil.createAccessToken(1L, UserRole.BASIC);

        mockMvc.perform(get(ADMIN_URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void ADMIN의_리프레시_토큰으로는_admin_API를_호출할_수_없다() throws Exception {
        String token = jwtUtil.createRefreshToken(1L, UserRole.ADMIN);

        mockMvc.perform(get(ADMIN_URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
