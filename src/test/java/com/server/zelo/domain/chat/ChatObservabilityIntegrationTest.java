package com.server.zelo.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.server.zelo.ai.gateway.FakeLlmGateway;
import com.server.zelo.common.auth.JwtUtil;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.repository.ChatRoomRepository;
import com.server.zelo.domain.user.entity.UserRole;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import com.server.zelo.domain.chat.service.ChatRateLimitService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

// 요청 하나가 응답 헤더(traceId), LLM 스레드의 로그 컨텍스트, Micrometer 지표까지 이어지는지 실제 스프링 구성으로 확인한다
@Transactional
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ChatObservabilityIntegrationTest {

    // 로컬 DB에 쌓인 데이터와 섞이지 않도록 실제로 쓰이지 않을 사용자 ID를 쓴다
    private static final Long OWNER_ID = 9_000_702L;

    // 횟수 제한은 ChatRateLimitService 테스트에서 따로 확인한다. 여기서는 같은 사용자로 1분에 10번 넘게 보내므로 끈다
    @MockitoBean
    private ChatRateLimitService chatRateLimitService;

    @Autowired
    private Tracer tracer;

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
    private ObservationRegistry observationRegistry;

    @Autowired
    @Qualifier("llmExecutorService")
    private ExecutorService llmExecutorService;

    // 스프링 컨텍스트는 테스트 클래스끼리 공유될 수 있으므로, 바꾼 Fake 모드와 서킷 기록을 되돌린다
    @AfterEach
    void tearDown() {
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.NORMAL);
        llmCircuitBreaker.reset();
        MDC.clear();
    }

    @Test
    void 응답_헤더에_요청의_traceId를_담는다() throws Exception {
        // given
        Long roomId = createRoom();

        // when & then
        send(roomId, "안녕")
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Trace-Id", matchesPattern("[0-9a-f]{16,32}")));
    }

    @Test
    void LLM이_실패하면_LLM_시도와_호출_FALLBACK_답변_처리_시간이_지표로_남는다() throws Exception {
        // given
        Long roomId = createRoom();
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.FAIL);
        double fallbackBefore = counter("zelo.chat.fallback", "code", "AI_004");
        long attemptBefore = timerCount("zelo.llm.attempt", "operation", "MENU_PLAN", "outcome", "AI_004");

        // when
        send(roomId, "안녕").andExpect(status().isBadGateway());

        // then: 재시도까지 두 번 시도하고, 사용자에게는 FALLBACK 하나로 끝난다
        assertThat(counter("zelo.chat.fallback", "code", "AI_004") - fallbackBefore).isEqualTo(1);
        assertThat(timerCount("zelo.llm.attempt", "operation", "MENU_PLAN", "outcome", "AI_004") - attemptBefore)
                .isEqualTo(2);
        assertThat(meterRegistry.get("zelo.chat.reply").tag("intent", "UNKNOWN").tag("outcome", "AI_004").timer().count())
                .isPositive();
    }

    @Test
    void 서킷브레이커_재시도_시간_제한의_상태가_지표로_나온다() {
        // when & then
        assertThat(meterRegistry.get("resilience4j.circuitbreaker.state").tag("name", "llm").gauges()).isNotEmpty();
        assertThat(meterRegistry.get("resilience4j.retry.calls").tag("name", "llm").functionCounters()).isNotEmpty();
        assertThat(meterRegistry.get("resilience4j.timelimiter.calls").tag("name", "llm").counters()).isNotEmpty();
    }

    @Test
    void LLM_스레드로_넘어가도_요청의_traceId와_사용자_채팅방이_로그_컨텍스트에_이어진다() throws Exception {
        // given: 실제 요청처럼 관측(Observation)을 열면 Micrometer Tracing이 span과 MDC의 traceId를 만든다
        Observation observation = Observation.start("request", observationRegistry);
        try (Observation.Scope ignored = observation.openScope()) {
            String traceId = tracer.currentSpan().context().traceId();
            MDC.put("userId", "1");
            MDC.put("roomId", "10");

            // when
            String[] seen = llmExecutorService.submit(() -> new String[] {
                    MDC.get("traceId"), MDC.get("userId"), MDC.get("roomId")}).get();

            // then
            assertThat(seen).containsExactly(traceId, "1", "10");
        } finally {
            observation.stop();
        }
    }

    private Long createRoom() {
        return chatRoomRepository.save(ChatRoom.of(OWNER_ID)).getId();
    }

    private ResultActions send(Long roomId, String text) throws Exception {
        return mockMvc.perform(post("/api/chat/rooms/{roomId}/messages", roomId)
                .header("Authorization", "Bearer " + jwtUtil.createAccessToken(OWNER_ID, UserRole.BASIC))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}"));
    }

    private double counter(String name, String... tags) {
        var counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private long timerCount(String name, String... tags) {
        var timer = meterRegistry.find(name).tags(tags).timer();
        return timer == null ? 0 : timer.count();
    }
}
