package com.server.zelo.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.server.zelo.ai.llm.FakeLlmGateway;
import com.server.zelo.common.auth.JwtUtil;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.entity.ChatSender;
import com.server.zelo.domain.chat.repository.ChatMessageRepository;
import com.server.zelo.domain.chat.repository.ChatRoomRepository;
import com.server.zelo.domain.chat.service.ChatMessageService;
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

// 새 대화 시작 API를 실제 JWT 필터부터 DB까지 검증한다. LLM은 test 프로필의 FakeLlmGateway가 대신한다
@Transactional
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ChatStartApiIntegrationTest {

    // 로컬 DB에 쌓인 데이터와 섞이지 않도록 실제로 쓰이지 않을 사용자 ID를 쓴다
    private static final Long USER_ID = 9_000_601L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private FakeLlmGateway fakeLlmGateway;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    // 스프링 컨텍스트는 테스트 클래스끼리 공유되므로, 바꾼 Fake 모드를 반드시 되돌린다
    @AfterEach
    void tearDown() {
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.NORMAL);
    }

    @Test
    void 새_대화를_시작하면_방_하나와_메시지_두_개가_생기고_새_방_ID와_AI_메시지를_반환한다() throws Exception {
        // when
        String body = start(USER_ID, "토요일 저녁에 넷이 먹을 불고기 재료 알려줘")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.roomId").isNumber())
                .andExpect(jsonPath("$.data.message.sender").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.message.content").value("[FAKE] 토요일 저녁에 넷이 먹을 불고기 재료 알려줘에 대한 답변입니다."))
                .andReturn().getResponse().getContentAsString();

        // then
        Long roomId = ((Number) JsonPath.read(body, "$.data.roomId")).longValue();
        assertThat(roomsOf(USER_ID)).extracting(ChatRoom::getId).containsExactly(roomId);
        assertThat(messagesOf(roomId)).extracting(ChatMessage::getSender)
                .containsExactly(ChatSender.USER, ChatSender.ASSISTANT);
    }

    @Test
    void 새_대화의_첫_메시지로_방_제목이_정해지고_방_목록에_보인다() throws Exception {
        // given
        String body = start(USER_ID, "불고기 재료").andReturn().getResponse().getContentAsString();
        Long roomId = ((Number) JsonPath.read(body, "$.data.roomId")).longValue();

        // when & then
        mockMvc.perform(get("/api/chat/rooms").header("Authorization", bearer(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].roomId").value(roomId))
                .andExpect(jsonPath("$.data[0].title").value("불고기 재료"));
    }

    @Test
    void 돌려받은_방_ID로_이어서_메시지를_보내고_목록을_조회할_수_있다() throws Exception {
        // given
        String body = start(USER_ID, "첫 메시지").andReturn().getResponse().getContentAsString();
        Long roomId = ((Number) JsonPath.read(body, "$.data.roomId")).longValue();

        // when
        mockMvc.perform(post("/api/chat/rooms/{roomId}/messages", roomId)
                        .header("Authorization", bearer(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"두 번째 메시지\"}"))
                .andExpect(status().isCreated());

        // then
        mockMvc.perform(get("/api/chat/rooms/{roomId}/messages", roomId).header("Authorization", bearer(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.length()").value(4))
                .andExpect(jsonPath("$.data.messages[3].content").value("첫 메시지"));
    }

    @Test
    // 테스트 트랜잭션 안에서는 커밋되지 않은 데이터도 보이므로, 트랜잭션 없이 실행해 실제 커밋을 검증한다
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void LLM이_실패하면_503이지만_방과_첫_메시지는_커밋되어_남아_있다() throws Exception {
        // given
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.FAIL);

        try {
            // when
            start(USER_ID, "안녕")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("AI_001"));

            // then: 사용자는 이 방에서 다시 보낼 수 있어야 한다
            List<ChatRoom> rooms = roomsOf(USER_ID);
            assertThat(rooms).hasSize(1);
            assertThat(messagesOf(rooms.getFirst().getId())).extracting(ChatMessage::getSender)
                    .containsExactly(ChatSender.USER);
        } finally {
            deleteAllOf(USER_ID);
        }
    }

    @Test
    // 방 생성과 첫 메시지 저장이 정말 한 트랜잭션인지 보려면 실제 커밋·롤백이 일어나야 하므로 트랜잭션 없이 실행한다
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 첫_메시지_저장이_실패하면_방도_남지_않는다() {
        try {
            // when: 요청 검증을 거치지 않고 서비스에 바로 빈 메시지를 넣어, 방을 만든 뒤 메시지 저장이 실패하게 한다
            assertThatThrownBy(() -> chatMessageService.createRoomWithFirstMessage(USER_ID, "  "))
                    .isInstanceOf(BusinessException.class);

            // then
            assertThat(roomsOf(USER_ID)).isEmpty();
        } finally {
            deleteAllOf(USER_ID);
        }
    }

    @Test
    void 첫_메시지가_비어_있으면_400이고_방을_만들지_않는다() throws Exception {
        // when & then
        start(USER_ID, "   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
        assertThat(roomsOf(USER_ID)).isEmpty();
    }

    @Test
    void 토큰_없이_새_대화를_시작하면_401이다() throws Exception {
        // when & then
        mockMvc.perform(post("/api/chat/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"안녕\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
    }

    private ResultActions start(Long userId, String text) throws Exception {
        return mockMvc.perform(post("/api/chat/messages")
                .header("Authorization", bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}"));
    }

    private String bearer(Long userId) {
        return "Bearer " + jwtUtil.createAccessToken(userId);
    }

    private List<ChatRoom> roomsOf(Long userId) {
        return chatRoomRepository.findAllByUserIdOrderByLastMessageAtDescIdDesc(userId);
    }

    private List<ChatMessage> messagesOf(Long roomId) {
        return chatMessageRepository.findAll().stream()
                .filter(message -> message.getRoomId().equals(roomId))
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .toList();
    }

    // 트랜잭션 없이 실행한 테스트는 롤백되지 않으므로 만든 데이터를 직접 지운다
    private void deleteAllOf(Long userId) {
        for (ChatRoom chatRoom : roomsOf(userId)) {
            chatMessageRepository.deleteAll(messagesOf(chatRoom.getId()));
            chatRoomRepository.delete(chatRoom);
        }
    }
}
