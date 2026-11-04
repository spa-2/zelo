package com.server.zelo.global.seed;

import com.server.zelo.domain.chat.dto.response.QuoteCardPayload;
import com.server.zelo.domain.product.entity.Keyword;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

// 부하 테스트용 지난 채팅 기록. ZELO_SEED_LOAD_TEST_ENABLED=true 로 켰을 때만 돈다
// 채팅 k6 사용자(5,001~15,000번)에게 방 1~20개(평균 10.5개), 방마다 메시지 10건(사용자 5 + AI 견적 카드 5)을 넣어 방 목록이 실제처럼 차 있게 한다
// 주문 시드(LoadTestOrderQuoteSeedLoader)는 15,001~20,000번 사용자와 90,000,000번대 방 ID를 쓰므로 사용자와 방 ID 모두 겹치지 않게 둔다
// orders.chat_room_id에 인덱스가 없어 방을 지우면 방마다 주문 테이블 전체를 확인해 매우 느리다. 그래서 지우고 다시 넣지 않고, 이미 있으면 건너뛴다
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "zelo.seed.load-test.enabled", havingValue = "true")
public class LoadTestChatSeedLoader {

    private static final int USER_FROM = 5_001;
    private static final int USER_TO = 15_000;
    private static final int MAX_ROOMS_PER_USER = 20;
    // 자동 증가하는 실제 채팅방 ID와 주문 시드(90,000,000번대) 사이의 번호대. 사용자마다 20칸씩 쓴다
    private static final long ROOM_ID_START = 80_000_000L;
    private static final long ROOM_ID_END = ROOM_ID_START + (long) (USER_TO - USER_FROM + 1) * MAX_ROOMS_PER_USER;
    private static final String MENU_NAME = "김치볶음밥";
    private static final String QUOTE_ID_PREFIX = "LT-CHAT-";
    private static final int CARD_PRODUCT_COUNT = 4;

    // 사용자마다 방 수가 다르게(1 + 사용자 ID % 20), 마지막 메시지 시각은 최근 30일(43,200분)에 흩어 둔다
    private static final String INSERT_CHAT_ROOMS = """
            insert into chat_rooms (id, user_id, title, last_message_at, created_at)
            select ? + (u - ?) * ? + n, u, '부하 테스트 대화 ' || n,
                   ?::timestamp - ((u * 20 + n) % 43200) * interval '1 minute', ?::timestamp - interval '30 days'
            from generate_series(?, ?) u, generate_series(1, ?) n
            where n <= 1 + u % ?""";

    // 방마다 사용자·AI가 번갈아 5번씩. AI 메시지는 견적 카드이고 견적 ID만 방·순서마다 다르게 바꾼다
    // 지난 견적은 실제로도 10분 뒤 만료돼 Redis에 없으므로 견적은 넣지 않는다
    private static final String INSERT_CHAT_MESSAGES = """
            insert into chat_messages (room_id, sender, content, payload, created_at)
            select r.id,
                   case when k % 2 = 1 then 'USER' else 'ASSISTANT' end,
                   case when k % 2 = 1
                        then (array['김치볶음밥 재료 준비해 줘', '4인분으로 해 줘', '맵지 않은 걸로 부탁해',
                                    '계란은 넉넉하게 넣어 줘', '이대로 다시 정리해 줘'])[(k + 1) / 2]
                        else '김치볶음밥 재료를 담았어요. 김치, 밥, 계란, 대파를 골랐어요.' end,
                   case when k % 2 = 0
                        then jsonb_set(?::jsonb, '{quoteId}', to_jsonb(?::text || r.id || '-' || k / 2)) end,
                   r.last_message_at - (10 - k) * interval '1 minute'
            from chat_rooms r, generate_series(1, 10) k
            where r.id > ? and r.id <= ?
            order by r.id, k""";

    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        Integer existing = jdbcTemplate.queryForObject(
                "select count(*) from chat_rooms where id > ? and id <= ?", Integer.class, ROOM_ID_START, ROOM_ID_END);
        if (existing != null && existing > 0) {
            log.info("부하 테스트 채팅 시드가 이미 있어서 건너뜀 rooms={}", existing);
            return;
        }
        List<QuoteCardPayload.Item> items = readCardItems();
        if (items.size() < CARD_PRODUCT_COUNT) {
            // 견적 카드에 담을 기본 시드 상품(zelo.seed.enabled)이 없으면 카드 형식을 만들 수 없다
            log.info("견적 카드에 담을 상품이 없어서 부하 테스트 채팅 시드 건너뜀 products={}", items.size());
            return;
        }

        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        int rooms = jdbcTemplate.update(INSERT_CHAT_ROOMS, ROOM_ID_START, USER_FROM, MAX_ROOMS_PER_USER,
                now, now, USER_FROM, USER_TO, MAX_ROOMS_PER_USER, MAX_ROOMS_PER_USER);
        int messages = jdbcTemplate.update(INSERT_CHAT_MESSAGES, cardTemplate(items), QUOTE_ID_PREFIX,
                ROOM_ID_START, ROOM_ID_END);
        // 통계가 없으면 실행 계획이 실제와 달라질 수 있어서 넣은 뒤 갱신함
        jdbcTemplate.execute("analyze chat_rooms");
        jdbcTemplate.execute("analyze chat_messages");
        log.info("부하 테스트 채팅 시드 저장 완료 rooms={} messages={}", rooms, messages);
    }

    private List<QuoteCardPayload.Item> readCardItems() {
        return jdbcTemplate.query("""
                        select id, name, price, content_amount, unit_label from products
                        where status = 'ON_SALE' order by id limit ?""",
                (rs, rowNum) -> new QuoteCardPayload.Item(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("content_amount") + rs.getString("unit_label"), rs.getLong("price"), 1,
                        rs.getLong("price"), null),
                CARD_PRODUCT_COUNT);
    }

    // 채팅이 저장하는 형식과 같도록 같은 QuoteCardPayload와 JsonMapper로 만든다. quoteId는 SQL에서 방마다 바꾼다
    private String cardTemplate(List<QuoteCardPayload.Item> items) {
        long totalAmount = items.stream().mapToLong(QuoteCardPayload.Item::lineAmount).sum();
        QuoteCardPayload payload = new QuoteCardPayload(QuoteCardPayload.TYPE, QUOTE_ID_PREFIX, MENU_NAME,
                List.of(Keyword.NOT_SPICY.getLabel()), items, List.of(), List.of(), null, false, List.of(),
                totalAmount, LocalDateTime.now().minusDays(30));
        return jsonMapper.writeValueAsString(payload);
    }
}
