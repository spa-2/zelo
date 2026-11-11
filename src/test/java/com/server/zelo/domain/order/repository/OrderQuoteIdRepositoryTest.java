package com.server.zelo.domain.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.domain.order.entity.ActorType;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.ShippingAddress;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.global.config.AuditingConfig;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// 실행마다 별도 스키마를 만들어 실제 PostgreSQL에서 IN 조회를 검증한다
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
        "spring.sql.init.mode=never",
        "spring.jpa.show-sql=false"
})
@ActiveProfiles("test")
@Import(AuditingConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderQuoteIdRepositoryTest {

    private static final String TEST_SCHEMA = "order_quote_id_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired private EntityManager entityManager;
    @Autowired private OrderRepository orderRepository;

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> TEST_SCHEMA);
    }

    @Test
    void 주문이_있는_견적_ID만_상태와_관계없이_돌려준다() {
        // given
        saveOrder("quote-paid", false);
        saveOrder("quote-canceled", true);
        saveOrder("quote-other-room", false);

        // when
        List<String> result = orderRepository.findQuoteIdsByQuoteIdIn(
                List.of("quote-paid", "quote-canceled", "quote-not-ordered"));

        // then
        assertThat(result).containsExactlyInAnyOrder("quote-paid", "quote-canceled");
    }

    private void saveOrder(String quoteId, boolean canceled) {
        User user = User.of("quote-" + UUID.randomUUID().toString().replace("-", "") + "@test.dev", "test-password", "견적 테스트", "01012345678");
        entityManager.persist(user);
        Order order = Order.of(user, quoteId, "견적 테스트 주문", ActorType.USER, null, 3_000L,
                ShippingAddress.of("홍길동", "01012345678", "12345", "주문 당시 주소"));
        if (canceled) {
            order.cancel();
        }
        entityManager.persist(order);
        entityManager.flush();
    }
}
