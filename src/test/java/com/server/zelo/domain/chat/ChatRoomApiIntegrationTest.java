package com.server.zelo.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.server.zelo.common.auth.JwtUtil;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.repository.ChatRoomRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// 실제 JWT 필터 → @Auth 리졸버 → 서비스 → DB(JPA Auditing 포함)까지 한 번에 검증한다
@Transactional
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ChatRoomApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Test
    void 토큰의_사용자로_채팅방이_저장되고_생성_시각이_채워진다() throws Exception {
        // given
        String token = jwtUtil.createAccessToken(1L);

        // when
        String body = mockMvc.perform(post("/api/chat/rooms").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.roomId").isNumber())
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andReturn().getResponse().getContentAsString();

        // then
        Long roomId = ((Number) JsonPath.read(body, "$.data.roomId")).longValue();
        ChatRoom saved = chatRoomRepository.findById(roomId).orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getTitle()).isNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void 같은_사용자가_두_번_생성하면_서로_다른_방이_만들어진다() throws Exception {
        // given
        String token = jwtUtil.createAccessToken(1L);

        // when
        String first = mockMvc.perform(post("/api/chat/rooms").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/chat/rooms").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();

        // then
        Number firstId = JsonPath.read(first, "$.data.roomId");
        Number secondId = JsonPath.read(second, "$.data.roomId");
        assertThat(firstId.longValue()).isNotEqualTo(secondId.longValue());
    }

    @Test
    void 토큰_없이_요청하면_401과_AUTH_001을_반환한다() throws Exception {
        // when & then
        mockMvc.perform(post("/api/chat/rooms"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
    }
}
