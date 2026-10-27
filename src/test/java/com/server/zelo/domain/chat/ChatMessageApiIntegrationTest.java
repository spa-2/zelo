package com.server.zelo.domain.chat;

import com.server.zelo.domain.user.entity.UserRole;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.server.zelo.ai.gateway.FakeLlmGateway;
import com.server.zelo.common.auth.JwtUtil;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.entity.ChatSender;
import com.server.zelo.domain.chat.repository.ChatMessageRepository;
import com.server.zelo.domain.chat.repository.ChatRoomRepository;
import com.server.zelo.domain.chat.service.ChatMessageService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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

    @Autowired
    private CircuitBreaker llmCircuitBreaker;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ChatMessageService chatMessageService;

    // 스프링 컨텍스트는 테스트 클래스끼리 공유되므로, 바꾼 Fake 모드와 쌓인 서킷 브레이커 기록을 반드시 되돌린다
    @AfterEach
    void tearDown() {
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.NORMAL);
        llmCircuitBreaker.reset();
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
    void LLM_서버가_실패하면_502와_AI_004에_고정_문구_메시지를_담고_사용자_메시지와_함께_커밋되어_남아_있다() throws Exception {
        // given
        Long roomId = createRoom(OWNER_ID);
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.FAIL);

        try {
            // when: 실패 코드는 상태·code로, 화면에 보여 줄 고정 문구 메시지는 data로 받는다
            send(OWNER_ID, roomId, "안녕")
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("AI_004"))
                    .andExpect(jsonPath("$.data.sender").value("ASSISTANT"))
                    .andExpect(jsonPath("$.data.payload.type").value("FALLBACK"))
                    .andExpect(jsonPath("$.data.payload.code").value("AI_004"));

            // then: 바깥 트랜잭션이 없으므로 여기서 조회되는 것은 커밋된 데이터뿐이다
            List<ChatMessage> messages = messagesOf(roomId);
            assertThat(messages).extracting(ChatMessage::getSender).containsExactly(ChatSender.USER, ChatSender.ASSISTANT);
            assertThat(messages.get(0).getContent()).isEqualTo("안녕");
            assertThat(messages.get(1).getPayload()).contains("FALLBACK");
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

    @Test
    void 목록은_최신순으로_나눠_받고_다음_커서로_이어_받으면_중복_없이_전부_받는다() throws Exception {
        // given: 메시지 3번 전송 → 사용자·AI 메시지 6개
        Long roomId = createRoom(OWNER_ID);
        for (int i = 1; i <= 3; i++) {
            send(OWNER_ID, roomId, "질문" + i).andExpect(status().isCreated());
        }
        List<Long> savedIdsNewestFirst = messagesOf(roomId).stream().map(ChatMessage::getId).toList().reversed();

        // when: 첫 페이지 4개
        String first = list(OWNER_ID, roomId, null, 4)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.length()").value(4))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andReturn().getResponse().getContentAsString();
        Number nextCursor = JsonPath.read(first, "$.data.nextCursor");

        // when: 다음 페이지
        String second = list(OWNER_ID, roomId, nextCursor.longValue(), 4)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.length()").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // then: 두 페이지를 이으면 저장된 메시지 전부가 최신순으로 한 번씩
        List<Long> received = new ArrayList<>();
        List<Number> firstIds = JsonPath.read(first, "$.data.messages[*].messageId");
        List<Number> secondIds = JsonPath.read(second, "$.data.messages[*].messageId");
        firstIds.forEach(id -> received.add(id.longValue()));
        secondIds.forEach(id -> received.add(id.longValue()));
        assertThat(received).containsExactlyElementsOf(savedIdsNewestFirst);
    }

    @Test
    void 전송_응답과_목록의_메시지는_같은_모양이다() throws Exception {
        // given
        Long roomId = createRoom(OWNER_ID);
        String sent = send(OWNER_ID, roomId, "김치볶음밥 재료")
                .andReturn().getResponse().getContentAsString();

        // when
        String listed = list(OWNER_ID, roomId, null, null)
                .andReturn().getResponse().getContentAsString();

        // then: 전송 응답(AI 메시지)과 목록의 맨 앞(가장 최근 = 같은 AI 메시지)의 필드와 값이 모두 같다
        Map<String, Object> sentMessage = JsonPath.read(sent, "$.data");
        Map<String, Object> newestInList = JsonPath.read(listed, "$.data.messages[0]");
        assertThat(newestInList).isEqualTo(sentMessage);
    }

    @Test
    void 메시지가_없는_방은_빈_목록을_반환한다() throws Exception {
        // given
        Long roomId = createRoom(OWNER_ID);

        // when & then
        list(OWNER_ID, roomId, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages").isEmpty())
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 다른_사용자의_방을_조회하면_403과_CHAT_002를_반환한다() throws Exception {
        // given
        Long roomId = createRoom(OWNER_ID);
        send(OWNER_ID, roomId, "내 메시지").andExpect(status().isCreated());

        // when & then
        list(OTHER_ID, roomId, null, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHAT_002"));
    }

    @Test
    void 없는_방을_조회하면_404와_CHAT_001을_반환한다() throws Exception {
        // when & then
        list(OWNER_ID, Long.MAX_VALUE, null, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT_001"));
    }

    @Test
    void 토큰_없이_목록을_조회하면_401과_AUTH_001을_반환한다() throws Exception {
        // given
        Long roomId = createRoom(OWNER_ID);

        // when & then
        mockMvc.perform(get("/api/chat/rooms/{roomId}/messages", roomId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
    }

    @Test
    void 대화_기록은_DB에서_읽은_고정_문구를_빼고_이번_메시지_앞의_대화를_오래된_순으로_준다() {
        // given
        Long roomId = createRoom(OWNER_ID);
        chatMessageService.createUserMessage(roomId, OWNER_ID, "김치볶음밥 재료 알려 줘");
        chatMessageService.createFallbackMessage(roomId, OWNER_ID, ErrorCode.AI_TIMEOUT);
        chatMessageService.createUserMessage(roomId, OWNER_ID, "다시 알려 줘");
        chatMessageService.createAssistantMessage(roomId, OWNER_ID, "김치, 밥, 계란이 필요해요.");
        ChatMessage current = chatMessageService.createUserMessage(roomId, OWNER_ID, "그거 맵지 않게 해 줘");
        // 저장한 객체가 아니라 DB(jsonb)가 다시 만든 payload를 읽도록 영속성 컨텍스트를 비운다
        entityManager.flush();
        entityManager.clear();

        // when
        List<ChatMessage> history = chatMessageService.findHistory(roomId, current.getId());

        // then
        assertThat(history).extracting(ChatMessage::getContent)
                .containsExactly("김치볶음밥 재료 알려 줘", "다시 알려 줘", "김치, 밥, 계란이 필요해요.");
    }

    private Long createRoom(Long userId) {
        return chatRoomRepository.save(ChatRoom.of(userId)).getId();
    }

    private ResultActions send(Long userId, Long roomId, String text) throws Exception {
        return mockMvc.perform(post("/api/chat/rooms/{roomId}/messages", roomId)
                .header("Authorization", "Bearer " + jwtUtil.createAccessToken(userId, UserRole.BASIC))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}"));
    }

    private ResultActions list(Long userId, Long roomId, Long before, Integer size) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/chat/rooms/{roomId}/messages", roomId)
                .header("Authorization", "Bearer " + jwtUtil.createAccessToken(userId, UserRole.BASIC));
        if (before != null) {
            request.param("before", before.toString());
        }
        if (size != null) {
            request.param("size", size.toString());
        }
        return mockMvc.perform(request);
    }

    private List<ChatMessage> messagesOf(Long roomId) {
        return chatMessageRepository.findAll().stream()
                .filter(message -> message.getRoomId().equals(roomId))
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .toList();
    }
}
