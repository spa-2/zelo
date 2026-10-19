package com.server.zelo.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.server.zelo.ai.llm.FakeLlmGateway;
import com.server.zelo.common.auth.JwtUtil;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.entity.ChatSender;
import com.server.zelo.domain.chat.repository.ChatMessageRepository;
import com.server.zelo.domain.chat.repository.ChatRoomRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 실제 JWT 필터 → 컨트롤러 → 전송 흐름 → DB까지 검증한다. LLM은 test 프로필의 FakeLlmGateway가 대신한다
@Transactional
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ChatMessageApiIntegrationTest {

    // 로컬 DB에 쌓인 데이터와 섞이지 않도록 실제로 쓰이지 않을 사용자 ID를 쓴다
    private static final Long OWNER_ID = 9_000_201L;
    private static final Long OTHER_ID = 9_000_202L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private FakeLlmGateway fakeLlmGateway;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    // 스프링 컨텍스트는 테스트 클래스끼리 공유되므로, 바꾼 Fake 모드를 반드시 되돌린다
    @AfterEach
    void tearDown() {
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.NORMAL);
    }

    @Test
    void 메시지를_보내면_사용자_메시지와_AI_메시지가_순서대로_저장되고_AI_메시지를_반환한다() throws Exception {
        // given
        Long roomId = createRoom(OWNER_ID);

        // when
        String body = send(OWNER_ID, roomId, "김치볶음밥 재료 준비해 줘")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sender").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.content").value("[FAKE] 김치볶음밥 재료 준비해 줘에 대한 답변입니다."))
                .andReturn().getResponse().getContentAsString();

        // then
        List<ChatMessage> messages = messagesOf(roomId);
        assertThat(messages).extracting(ChatMessage::getSender).containsExactly(ChatSender.USER, ChatSender.ASSISTANT);
        assertThat(messages.get(0).getContent()).isEqualTo("김치볶음밥 재료 준비해 줘");
        Number messageId = JsonPath.read(body, "$.data.messageId");
        assertThat(messages.get(1).getId()).isEqualTo(messageId.longValue());
    }

    @Test
    void 첫_메시지로_방_제목이_정해지고_마지막_대화_시각이_갱신된다() throws Exception {
        // given
        Long roomId = createRoom(OWNER_ID);
        LocalDateTime createdLastMessageAt = chatRoomRepository.findById(roomId).orElseThrow().getLastMessageAt();

        // when
        send(OWNER_ID, roomId, "토요일 저녁 메뉴").andExpect(status().isCreated());
        send(OWNER_ID, roomId, "두 번째 메시지").andExpect(status().isCreated());

        // then
        ChatRoom chatRoom = chatRoomRepository.findById(roomId).orElseThrow();
        assertThat(chatRoom.getTitle()).isEqualTo("토요일 저녁 메뉴");
        assertThat(chatRoom.getLastMessageAt()).isAfter(createdLastMessageAt);
    }

    @Test
    // 클래스의 테스트 트랜잭션 안에서는 커밋되지 않은 데이터도 보이므로, 이 테스트만 트랜잭션 없이 실행해 실제 커밋을 검증한다
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void LLM이_실패하면_503과_AI_001을_반환하지만_사용자_메시지는_커밋되어_남아_있다() throws Exception {
        // given
        Long roomId = createRoom(OWNER_ID);
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.FAIL);

        try {
            // when
            send(OWNER_ID, roomId, "안녕")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("AI_001"));

            // then: 바깥 트랜잭션이 없으므로 여기서 조회되는 것은 커밋된 데이터뿐이다
            List<ChatMessage> messages = messagesOf(roomId);
            assertThat(messages).extracting(ChatMessage::getSender).containsExactly(ChatSender.USER);
            assertThat(messages.get(0).getContent()).isEqualTo("안녕");
        } finally {
            // 롤백되지 않으므로 테스트가 실패해도 만든 데이터를 직접 지운다
            chatMessageRepository.deleteAll(messagesOf(roomId));
            chatRoomRepository.deleteById(roomId);
        }
    }

    @Test
    void 다른_사용자의_방에_보내면_403과_CHAT_002를_반환하고_저장하지_않는다() throws Exception {
        // given
        Long roomId = createRoom(OWNER_ID);

        // when & then
        send(OTHER_ID, roomId, "남의 방")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHAT_002"));
        assertThat(messagesOf(roomId)).isEmpty();
    }

    @Test
    void 없는_방에_보내면_404와_CHAT_001을_반환한다() throws Exception {
        // when & then
        send(OWNER_ID, Long.MAX_VALUE, "없는 방")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT_001"));
    }

    @Test
    void 토큰_없이_보내면_401과_AUTH_001을_반환한다() throws Exception {
        // given
        Long roomId = createRoom(OWNER_ID);

        // when & then
        mockMvc.perform(post("/api/chat/rooms/{roomId}/messages", roomId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"안녕\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
    }

    private Long createRoom(Long userId) {
        return chatRoomRepository.save(ChatRoom.of(userId)).getId();
    }

    private ResultActions send(Long userId, Long roomId, String text) throws Exception {
        return mockMvc.perform(post("/api/chat/rooms/{roomId}/messages", roomId)
                .header("Authorization", "Bearer " + jwtUtil.createAccessToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}"));
    }

    private List<ChatMessage> messagesOf(Long roomId) {
        return chatMessageRepository.findAll().stream()
                .filter(message -> message.getRoomId().equals(roomId))
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .toList();
    }
}
