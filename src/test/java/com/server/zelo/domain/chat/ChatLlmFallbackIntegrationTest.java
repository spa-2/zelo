package com.server.zelo.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.server.zelo.ai.gateway.FakeLlmGateway;
import com.server.zelo.common.auth.JwtUtil;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.entity.ChatSender;
import com.server.zelo.domain.chat.repository.ChatMessageRepository;
import com.server.zelo.domain.chat.repository.ChatRoomRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

// LLM이 느리거나 실패할 때 메시지 전송 API가 실제 시간 제한·재시도·서킷 브레이커를 거쳐 실패 코드(5xx)와 고정 문구로 답하는지 검증한다.
// LLM은 test 프로필의 FakeLlmGateway가 대신하고, 시간 제한은 test 프로필 값(500ms), 서킷 수치는 실제 설정을 쓴다
@Transactional
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ChatLlmFallbackIntegrationTest {

    // 로컬 DB에 쌓인 데이터와 섞이지 않도록 실제로 쓰이지 않을 사용자 ID를 쓴다
    private static final Long USER_ID = 9_000_701L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private FakeLlmGateway fakeLlmGateway;

    @Autowired
    private CircuitBreaker llmCircuitBreaker;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Value("${zelo.llm.timeout}")
    private Duration timeout;

    @Value("${zelo.llm.circuit-breaker.permitted-number-of-calls-in-half-open-state}")
    private int permittedCalls;

    // 스프링 컨텍스트는 테스트 클래스끼리 공유되므로, 앞선 테스트가 남긴 서킷 기록 없이 시작하고 끝나면 되돌린다
    @BeforeEach
    void setUp() {
        llmCircuitBreaker.reset();
    }

    @AfterEach
    void tearDown() {
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.NORMAL);
        fakeLlmGateway.changeDelay(Duration.ofSeconds(6));
        llmCircuitBreaker.reset();
    }

    @Test
    void LLM이_시간_안에_답하지_않으면_재시도_후_504와_고정_문구로_답하고_사용자_메시지는_남는다() throws Exception {
        // given
        Long roomId = createRoom();
        fakeLlmGateway.changeDelay(timeout.multipliedBy(4));
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.DELAY);
        long start = System.nanoTime();

        // when
        send(roomId, "김치볶음밥 재료")
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AI_002"))
                .andExpect(jsonPath("$.data.sender").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.payload.type").value("FALLBACK"))
                .andExpect(jsonPath("$.data.payload.code").value("AI_002"));

        // then: 시도 2번 모두 시간 제한에서 끊겨, 늦은 응답을 다 기다리지 않는다
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(elapsed).isGreaterThanOrEqualTo(timeout.multipliedBy(2)).isLessThan(timeout.multipliedBy(4));
        assertThat(llmCircuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
        List<ChatMessage> messages = messagesOf(roomId);
        assertThat(messages).extracting(ChatMessage::getSender).containsExactly(ChatSender.USER, ChatSender.ASSISTANT);
        assertThat(messages.get(0).getContent()).isEqualTo("김치볶음밥 재료");
    }

    @Test
    void 서버_오류는_한_번_재시도하고_요청_거절은_재시도하지_않으며_둘_다_502다() throws Exception {
        // given
        Long roomId = createRoom();

        // when: 서버 오류(5xx)
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.FAIL);
        send(roomId, "첫 질문")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_004"))
                .andExpect(jsonPath("$.data.payload.type").value("FALLBACK"));
        int failedAfterServerError = llmCircuitBreaker.getMetrics().getNumberOfFailedCalls();

        // when: 요청 거절(4xx)
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.REJECT);
        send(roomId, "두 번째 질문")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_006"))
                .andExpect(jsonPath("$.data.payload.type").value("FALLBACK"));

        // then: 서버 오류는 재시도까지 2번 실패로 세고, 요청 거절은 재시도하지 않으며 서킷 실패로도 세지 않는다
        assertThat(failedAfterServerError).isEqualTo(2);
        assertThat(llmCircuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
    }

    @Test
    void 요청_거절이_반복돼도_서킷이_열리지_않아_다른_요청은_정상으로_답한다() throws Exception {
        // given: 최소 시도 수(5)를 넘게 요청 거절(4xx)을 받는다
        Long roomId = createRoom();
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.REJECT);
        for (int i = 1; i <= 6; i++) {
            send(roomId, "거절될 질문" + i)
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("AI_006"));
        }

        // when
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.NORMAL);

        // then
        send(roomId, "정상 질문")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.content").value("[FAKE] 정상 질문에 대한 답변입니다."));
        assertThat(llmCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void 연속으로_실패하면_서킷이_열려_LLM이_회복되어도_열려_있는_동안은_바로_503과_고정_문구로_답한다() throws Exception {
        // given: 메시지 하나가 재시도까지 실패하면 2번으로 세므로 3개면 최소 시도 수(5)와 실패율(50%)을 넘는다
        Long roomId = createRoom();
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.FAIL);
        for (int i = 1; i <= 3; i++) {
            send(roomId, "질문" + i).andExpect(status().is5xxServerError());
        }
        assertThat(llmCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // when: LLM은 다시 정상이지만 서킷이 열려 있어 부르지 않는다
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.NORMAL);
        send(roomId, "회복 후 질문")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_008"))
                .andExpect(jsonPath("$.data.payload.type").value("FALLBACK"));

        // then
        assertThat(llmCircuitBreaker.getMetrics().getNumberOfNotPermittedCalls()).isPositive();
        assertThat(messagesOf(roomId)).hasSize(8);
    }

    @Test
    void 열린_시간이_지나_시험_호출이_모두_성공하면_서킷이_닫히고_다시_AI_답변을_준다() throws Exception {
        // given: 열린 시간(30초)을 기다리는 대신 반쯤 열린 상태로 바로 옮긴다
        Long roomId = createRoom();
        llmCircuitBreaker.transitionToOpenState();
        llmCircuitBreaker.transitionToHalfOpenState();

        // when
        for (int i = 1; i <= permittedCalls; i++) {
            send(roomId, "시험 질문" + i)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.content").value("[FAKE] 시험 질문" + i + "에 대한 답변입니다."))
                    .andExpect(jsonPath("$.data.payload").doesNotExist());
        }

        // then
        assertThat(llmCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void 방을_다시_열어도_고정_문구_메시지는_payload와_함께_그대로_복원된다() throws Exception {
        // given
        Long roomId = createRoom();
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.FAIL);
        send(roomId, "안녕").andExpect(status().isBadGateway());

        // when & then
        mockMvc.perform(get("/api/chat/rooms/{roomId}/messages", roomId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages[0].sender").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.messages[0].payload.type").value("FALLBACK"))
                .andExpect(jsonPath("$.data.messages[1].sender").value("USER"))
                .andExpect(jsonPath("$.data.messages[1].payload").doesNotExist());
    }

    private Long createRoom() {
        return chatRoomRepository.save(ChatRoom.of(USER_ID)).getId();
    }

    private ResultActions send(Long roomId, String text) throws Exception {
        return mockMvc.perform(post("/api/chat/rooms/{roomId}/messages", roomId)
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}"));
    }

    private String bearer() {
        return "Bearer " + jwtUtil.createAccessToken(USER_ID);
    }

    private List<ChatMessage> messagesOf(Long roomId) {
        return chatMessageRepository.findAll().stream()
                .filter(message -> message.getRoomId().equals(roomId))
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .toList();
    }
}
