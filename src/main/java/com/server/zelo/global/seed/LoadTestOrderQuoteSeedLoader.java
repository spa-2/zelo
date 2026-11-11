package com.server.zelo.global.seed;

import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import com.server.zelo.global.seed.LoadTestOrderSeedSupport.ProductCursor;
import com.server.zelo.global.seed.LoadTestOrderSeedSupport.SeedItem;
import com.server.zelo.global.seed.LoadTestOrderSeedSupport.SeedProduct;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

// 부하 테스트용 주문 직전 상태(채팅방 + 견적). ZELO_SEED_LOAD_TEST_ENABLED=true 로 켰을 때만 돈다
// 주문은 k6가 주문 API로 만들므로 넣지 않는다. 견적은 한 번 주문하면 다시 못 쓰므로 켤 때마다 지난 주문·채팅방을 지우고 새로 넣는다 (재시작 = 초기화)
// k6가 사용자 번호만으로 요청을 만들 수 있게 채팅방 ID는 ROOM_ID_START + 사용자 ID, 견적 ID는 LT-ORD-{사용자 ID 7자리}로 고정한다
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "zelo.seed.load-test.enabled", havingValue = "true")
public class LoadTestOrderQuoteSeedLoader {

    private static final String PREFIX = "LT-ORD-";
    private static final String QUOTE_KEY_PREFIX = "quote:";
    private static final String MENU_NAME = "부하 테스트 메뉴";
    // 다른 파트 VU와 같은 사용자를 쓰면 주문 상태가 서로 꼬이므로 팀에서 나눈 범위만 쓴다
    // 주문은 사용자당 1건이라 비어 있던 27,001~30,000(소셜 가입)까지 붙여 8,000명을 쓴다
    private static final List<UserRange> USER_RANGES = List.of(new UserRange(15_001, 20_000), new UserRange(27_001, 30_000));
    // 자동 증가하는 실제 채팅방 ID와 겹치지 않을 번호대
    private static final long ROOM_ID_START = 90_000_000L;
    // 실제 견적 TTL(10분)이면 테스트 도중 만료되므로 길게 둔다
    private static final Duration QUOTE_TTL = Duration.ofHours(3);

    private static final String INSERT_CHAT_ROOM = """
            insert into chat_rooms (id, user_id, title, last_message_at, created_at)
            values (?, ?, '부하 테스트 주문', ?, ?)""";

    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final LoadTestOrderSeedSupport loadTestOrderSeedSupport;

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        List<SeedProduct> products = loadTestOrderSeedSupport.readProducts();
        if (products.size() < LoadTestOrderSeedSupport.MIN_PRODUCT_COUNT) {
            // 상품 시드도 같은 시점에 돌아서 처음 켤 때는 상품이 아직 없을 수 있다. 상품 시드가 끝난 뒤 다시 켜면 들어간다
            log.info("부하 테스트 상품이 없어서 주문 시드 건너뜀 products={}", products.size());
            return;
        }
        // 주문이 채팅방을 참조하므로 주문부터 지운다
        loadTestOrderSeedSupport.deleteOrders(PREFIX);
        deleteChatRooms();

        LocalDateTime now = LocalDateTime.now();
        // 결제·환불 시드와 다른 상품부터 골라 같은 재고 행에 겹치지 않게 한다
        ProductCursor cursor = new ProductCursor(products, products.size() / 4);
        List<Quote> quotes = USER_RANGES.stream()
                .flatMapToInt(range -> IntStream.rangeClosed(range.from(), range.to()))
                .mapToObj(userId -> quote(userId, cursor.items(1 + userId % 3, userId), now))
                .toList();
        loadTestOrderSeedSupport.saveInChunks(quotes, chunk -> saveChatRooms(chunk, Timestamp.valueOf(now)));
        quotes.forEach(this::saveQuote);

        log.info("부하 테스트 주문 시드 저장 완료 quotes={}", quotes.size());
    }

    private void deleteChatRooms() {
        for (UserRange range : USER_RANGES) {
            long fromRoomId = ROOM_ID_START + range.from();
            long toRoomId = ROOM_ID_START + range.to();
            jdbcTemplate.update("delete from chat_messages where room_id between ? and ?", fromRoomId, toRoomId);
            jdbcTemplate.update("delete from chat_rooms where id between ? and ?", fromRoomId, toRoomId);
        }
    }

    // 주문 API가 품목 가격·합계를 상품과 다시 비교하므로 DB의 현재 가격으로 맞춘다
    private Quote quote(int userId, List<SeedItem> items, LocalDateTime now) {
        List<QuoteItem> quoteItems = items.stream()
                .map(item -> new QuoteItem(item.product().id(), item.product().code(), item.product().name(),
                        item.product().unit(), item.product().price(), item.quantity(), item.amount()))
                .toList();
        long totalAmount = items.stream().mapToLong(SeedItem::amount).sum();
        return new Quote(LoadTestOrderSeedSupport.quoteId(PREFIX, userId), (long) userId, ROOM_ID_START + userId,
                MENU_NAME, quoteItems, List.of(), totalAmount, now, now.plus(QUOTE_TTL));
    }

    private void saveChatRooms(List<Quote> chunk, Timestamp now) {
        loadTestOrderSeedSupport.batch(INSERT_CHAT_ROOM, chunk.stream()
                .map(quote -> new Object[]{quote.roomId(), quote.userId(), now, now})
                .toList());
    }

    // 채팅이 저장하는 형식과 같도록 같은 JsonMapper로 직렬화한다
    private void saveQuote(Quote quote) {
        stringRedisTemplate.opsForValue()
                .set(QUOTE_KEY_PREFIX + quote.roomId(), jsonMapper.writeValueAsString(quote), QUOTE_TTL);
    }

    private record UserRange(int from, int to) {
    }
}
