package com.server.zelo.domain.chat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.server.zelo.ai.llm.FakeLlmGateway;
import com.server.zelo.ai.llm.MenuPlanIntent;
import com.server.zelo.common.auth.JwtUtil;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.repository.ChatRoomRepository;
import com.server.zelo.domain.quote.model.ExcludedItem;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import com.server.zelo.domain.quote.service.QuoteCommandService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

// 채팅 메시지 → 메뉴 계획(Fake) → 견적 발급 → 견적 카드 메시지 저장·응답까지를 실제 JWT·컨트롤러·DB로 확인한다.
// 견적 발급 결과는 DB의 상품 데이터(로컬은 시드 있음, CI는 비어 있음)에 따라 달라지므로 고정값으로 바꿔 둔다
@Transactional
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ChatQuoteApiIntegrationTest {

    // 로컬 DB에 쌓인 데이터와 섞이지 않도록 실제로 쓰이지 않을 사용자 ID를 쓴다
    private static final Long OWNER_ID = 9_000_701L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 23, 12, 0);

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

    @MockitoBean
    private QuoteCommandService quoteCommandService;

    @BeforeEach
    void setUp() {
        fakeLlmGateway.changePlanIntent(MenuPlanIntent.SHOPPING_REQUEST);
    }

    // 스프링 컨텍스트는 테스트 클래스끼리 공유될 수 있으므로, 바꾼 Fake 판단과 서킷 기록을 반드시 되돌린다
    @AfterEach
    void tearDown() {
        fakeLlmGateway.changePlanIntent(MenuPlanIntent.GENERAL_QUESTION);
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.NORMAL);
        llmCircuitBreaker.reset();
    }

    @Test
    void 장보기_요청을_보내면_안내_문장과_견적_카드가_담긴_AI_메시지를_201로_돌려준다() throws Exception {
        // given
        Long roomId = createRoom();
        given(quoteCommandService.createQuote(eq(OWNER_ID), eq(roomId), any())).willReturn(quote(roomId, List.of(
                new QuoteItem(1L, "SID-0008", "백김치", "1kg", 12_000L, 1, 12_000L)),
                List.of(new ExcludedItem("소주", "CATEGORY_NOT_ALLOWED"))));

        // when & then
        send(roomId, "김치볶음밥 재료 준비해 줘")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sender").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.content").value(startsWith("[FAKE] 메뉴: 김치볶음밥 (4인분)")))
                .andExpect(jsonPath("$.data.payload.type").value("QUOTE_CARD"))
                .andExpect(jsonPath("$.data.payload.quoteId").value("quote-1"))
                .andExpect(jsonPath("$.data.payload.items[0].productName").value("백김치"))
                .andExpect(jsonPath("$.data.payload.items[0].lineAmount").value(12_000))
                .andExpect(jsonPath("$.data.payload.excludedItems[0].reason").value("CATEGORY_NOT_ALLOWED"))
                .andExpect(jsonPath("$.data.payload.totalAmount").value(12_000))
                .andExpect(jsonPath("$.data.payload.buttons[0].action").value("YES"))
                .andExpect(jsonPath("$.data.payload.userId").doesNotExist());
    }

    @Test
    void 방을_다시_열어도_그때_본_견적_카드가_그대로_보인다() throws Exception {
        // given
        Long roomId = createRoom();
        given(quoteCommandService.createQuote(eq(OWNER_ID), eq(roomId), any())).willReturn(quote(roomId, List.of(
                new QuoteItem(1L, "SID-0008", "백김치", "1kg", 12_000L, 1, 12_000L)), List.of()));
        send(roomId, "김치볶음밥 재료 준비해 줘").andExpect(status().isCreated());

        // when & then: 최신순이라 첫 번째가 AI 메시지
        mockMvc.perform(get("/api/chat/rooms/{roomId}/messages", roomId)
                        .header("Authorization", "Bearer " + jwtUtil.createAccessToken(OWNER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages[0].payload.type").value("QUOTE_CARD"))
                .andExpect(jsonPath("$.data.messages[0].payload.items[0].productName").value("백김치"))
                .andExpect(jsonPath("$.data.messages[1].sender").value("USER"));
    }

    @Test
    void 담은_상품이_없으면_카드_없이_안내만_돌려준다() throws Exception {
        // given
        Long roomId = createRoom();
        given(quoteCommandService.createQuote(eq(OWNER_ID), eq(roomId), any())).willReturn(quote(roomId, List.of(),
                List.of(new ExcludedItem("김치", "OUT_OF_STOCK"))));

        // when & then
        send(roomId, "김치볶음밥 재료 준비해 줘")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.content").value(containsString("담은 상품: 없음")))
                .andExpect(jsonPath("$.data.payload").doesNotExist());
    }

    @Test
    void 견적_수정_판단이면_견적을_발급하지_않고_정해진_문장으로_답한다() throws Exception {
        // given
        Long roomId = createRoom();
        fakeLlmGateway.changePlanIntent(MenuPlanIntent.QUOTE_EDIT);

        // when & then
        send(roomId, "계란 빼줘")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.content").value("지금은 견적을 부분만 고칠 수 없어요. 바꾸고 싶은 내용을 담아 다시 요청해 주세요."))
                .andExpect(jsonPath("$.data.payload").doesNotExist());
        then(quoteCommandService).shouldHaveNoInteractions();
    }

    private Long createRoom() {
        return chatRoomRepository.save(ChatRoom.of(OWNER_ID)).getId();
    }

    private ResultActions send(Long roomId, String text) throws Exception {
        return mockMvc.perform(post("/api/chat/rooms/{roomId}/messages", roomId)
                .header("Authorization", "Bearer " + jwtUtil.createAccessToken(OWNER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}"));
    }

    private static Quote quote(Long roomId, List<QuoteItem> items, List<ExcludedItem> excludedItems) {
        long total = items.stream().mapToLong(QuoteItem::lineAmount).sum();
        return new Quote("quote-1", OWNER_ID, roomId, "김치볶음밥", items, excludedItems, total,
                CREATED_AT, CREATED_AT.plusMinutes(10));
    }
}
