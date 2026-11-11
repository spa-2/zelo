package com.server.zelo.domain.quote.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.MenuPlanIngredient;
import com.server.zelo.ai.plan.MenuPlanIntent;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.quote.repository.QuoteRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// Redis가 느리거나 멈추면 견적 요청이 Redis를 기다리는데, 그동안 DB 커넥션을 잡고 있으면 커넥션 풀이 금방 바닥난다
// 견적을 Redis에서 읽거나 저장하는 순간에 이 요청이 잡은 DB 커넥션이 없는지 본다
@SpringBootTest
@ActiveProfiles("test")
// 스케줄러가 도중에 커넥션을 잡으면 개수가 흔들려서 끈다
@TestPropertySource(properties = "zelo.scheduler.enabled=false")
class QuoteCommandServiceConnectionTest {

    private static final Long USER_ID = 1L;
    private static final Long ROOM_ID = 1L;

    @Autowired
    private QuoteCommandService quoteCommandService;

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    private QuoteRepository quoteRepository;

    private final List<Integer> activeConnectionsAtRedis = new ArrayList<>();

    @BeforeEach
    void setUp() throws SQLException {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        given(quoteRepository.findByRoomId(any())).willAnswer(invocation -> {
            activeConnectionsAtRedis.add(hikari.getHikariPoolMXBean().getActiveConnections());
            return Optional.empty();
        });
        willAnswer(invocation -> {
            activeConnectionsAtRedis.add(hikari.getHikariPoolMXBean().getActiveConnections());
            return null;
        }).given(quoteRepository).save(any());
    }

    @Test
    void 견적을_발급할_때_Redis에_저장하는_동안_DB_커넥션을_잡지_않는다() {
        // given
        MenuPlan menuPlan = MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, "김치볶음밥", 2, null,
                List.of(MenuPlanIngredient.of("김치", 400, BaseUnit.G)), List.of(), List.of());

        // when
        quoteCommandService.createQuote(USER_ID, ROOM_ID, menuPlan);

        // then
        assertThat(activeConnectionsAtRedis).containsExactly(0);
    }

    @Test
    void 견적을_수정할_때_Redis에서_읽는_동안_DB_커넥션을_잡지_않는다() {
        // given
        List<Runnable> edits = List.of(
                () -> quoteCommandService.changeQuantity(USER_ID, ROOM_ID, 1L, 2),
                () -> quoteCommandService.addItem(USER_ID, ROOM_ID, "김치", 400, BaseUnit.G),
                () -> quoteCommandService.recommendItems(USER_ID, ROOM_ID,
                        List.of(MenuPlanIngredient.of("김치", 400, BaseUnit.G))),
                () -> quoteCommandService.acceptSubstitute(USER_ID, ROOM_ID, "김치"),
                () -> quoteCommandService.applyBudgetOption(USER_ID, ROOM_ID, 1L));

        // when
        // 저장된 견적이 없어서 모두 QUOTE_EXPIRED로 끝나지만, 그 전에 Redis를 한 번씩 읽는다
        edits.forEach(edit -> assertThatThrownBy(edit::run).isInstanceOf(BusinessException.class));

        // then
        assertThat(activeConnectionsAtRedis).containsExactly(0, 0, 0, 0, 0);
    }
}
