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

// /api/admin/** 의 RBAC만 확인하므로 실제 admin API가 없어도 되도록 인가 단계의 결과(401/403, 통과 시 404)로 판단한다
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminAccessIntegrationTest {

    private static final String ADMIN_URL = "/api/admin/anything";

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
    void ADMIN_사용자는_인가를_통과한다() throws Exception {
        String token = jwtUtil.createAccessToken(1L, UserRole.ADMIN);

        // 핸들러가 없어서 404지만, 401/403이 아니면 인가는 통과한 것
        mockMvc.perform(get(ADMIN_URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void ADMIN의_리프레시_토큰으로는_admin_API를_호출할_수_없다() throws Exception {
        String token = jwtUtil.createRefreshToken(1L, UserRole.ADMIN);

        mockMvc.perform(get(ADMIN_URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
