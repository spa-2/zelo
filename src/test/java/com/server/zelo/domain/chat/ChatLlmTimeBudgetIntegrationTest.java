package com.server.zelo.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.server.zelo.domain.chat.service.ChatRateLimitService;
import com.server.zelo.domain.user.entity.UserRole;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

// 메시지 전송 API가 메시지 하나의 시간 예산(LlmTimeBudget) 안에서 LLM을 부르는지 검증한다.
// 시도당 제한은 test 프로필 값(500ms)이고, 예산은 첫 시도를 다 기다리면 재시도할 시간이 남지 않게 700ms로 줄인다
@Transactional
@SpringBootTest(properties = {
        "zelo.llm.time-budget.total=700ms",
        "zelo.llm.time-budget.min-attempt=200ms"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ChatLlmTimeBudgetIntegrationTest {

    // 로컬 DB에 쌓인 데이터와 섞이지 않도록 실제로 쓰이지 않을 사용자 ID를 쓴다
    private static final Long USER_ID = 9_000_711L;

    // 횟수 제한은 ChatRateLimitService 테스트에서 따로 확인한다
    @MockitoBean
    private ChatRateLimitService chatRateLimitService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private MeterRegistry meterRegistry;

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

    // 스프링 컨텍스트는 테스트 클래스끼리 공유될 수 있으므로, 서킷 기록 없이 시작하고 끝나면 되돌린다
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
    void 첫_시도를_다_기다린_뒤_예산이_모자라면_재시도하지_않고_504와_고정_문구로_답한다() throws Exception {
        // given
        Long roomId = createRoom();
        fakeLlmGateway.changeDelay(timeout.multipliedBy(4));
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.DELAY);
        long attemptsBefore = attempts();
        long start = System.nanoTime();

        // when
        send(roomId, "김치볶음밥 재료")
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("AI_002"))
                .andExpect(jsonPath("$.data.payload.type").value("FALLBACK"))
                .andExpect(jsonPath("$.data.payload.code").value("AI_002"))
                .andExpect(jsonPath("$.data.retryable").value(true));

        // then: 예산이 없으면 시도 2번(500ms + 10ms + 500ms)을 기다리지만, 남은 약 200ms로는 재시도하지 않고 바로 답한다
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(elapsed).isLessThan(timeout.multipliedBy(2));
        assertThat(attempts() - attemptsBefore).isEqualTo(1);
        List<ChatMessage> messages = messagesOf(roomId);
        assertThat(messages).extracting(ChatMessage::getSender).containsExactly(ChatSender.USER, ChatSender.ASSISTANT);
    }

    @Test
    void 예산_안에서_답하면_지금처럼_정상으로_답한다() throws Exception {
        // given
        Long roomId = createRoom();

        // when & then
        send(roomId, "안녕")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sender").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.content").value("[FAKE] 안녕에 대한 답변입니다."));
        assertThat(meterRegistry.find("zelo.llm.time.budget.exceeded").counter()).isNull();
    }

    private long attempts() {
        Timer timer = meterRegistry.find("zelo.llm.attempt").tag("operation", "MENU_PLAN").tag("outcome", "AI_002").timer();
        return timer == null ? 0 : timer.count();
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
        return "Bearer " + jwtUtil.createAccessToken(USER_ID, UserRole.BASIC);
    }

    private List<ChatMessage> messagesOf(Long roomId) {
        return chatMessageRepository.findAll().stream()
                .filter(message -> message.getRoomId().equals(roomId))
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .toList();
    }
}
