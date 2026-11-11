package com.server.zelo.domain.refund.repository;

import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.refund.dto.response.OrderRefundAmount;
import com.server.zelo.domain.refund.dto.response.RefundFindTarget;
import com.server.zelo.domain.refund.entity.Refund;
import com.server.zelo.domain.refund.entity.RefundStatus;
import com.server.zelo.domain.refund.entity.RefundType;
import com.server.zelo.global.config.AuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 실행마다 별도 스키마를 만들어 실제 PostgreSQL에서 결제 ID 조인과 합계를 검증한다
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
class RefundRepositoryTest {

    private static final String TEST_SCHEMA = "refund_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired private EntityManager entityManager;
    @Autowired private RefundRepository refundRepository;

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> TEST_SCHEMA);
    }

    @Test
    void 요청한_주문들의_완료된_환불_금액만_주문별로_더한다() {
        // given
        Long firstPaymentId = savePayment(1L);
        Long secondPaymentId = savePayment(2L);
        Long otherPaymentId = savePayment(3L);
        saveRefund(firstPaymentId, 1_000L, RefundStatus.COMPLETED);
        saveRefund(firstPaymentId, 2_000L, RefundStatus.COMPLETED);
        saveRefund(firstPaymentId, 500L, RefundStatus.FAILED);
        saveRefund(secondPaymentId, 700L, RefundStatus.COMPLETED);
        saveRefund(otherPaymentId, 900L, RefundStatus.COMPLETED);
        entityManager.flush();
        entityManager.clear();

        // when
        List<OrderRefundAmount> result = refundRepository.findRefundedAmountsByOrderIds(
                List.of(1L, 2L), RefundStatus.COMPLETED);

        // then
        assertThat(result).containsExactlyInAnyOrder(
                new OrderRefundAmount(1L, 3_000L),
                new OrderRefundAmount(2L, 700L));
    }

    @Test
    void 완료된_환불이_없는_주문은_결과에_없다() {
        // given
        Long paymentId = savePayment(1L);
        saveRefund(paymentId, 1_000L, RefundStatus.REQUESTED);
        entityManager.flush();
        entityManager.clear();

        // when
        List<OrderRefundAmount> result = refundRepository.findRefundedAmountsByOrderIds(
                List.of(1L), RefundStatus.COMPLETED);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void 기준_시각보다_오래된_요청_중_환불만_주문_ID와_포트원_결제_ID로_꺼낸다() {
        // given
        Long requestedPaymentId = savePayment(1L);
        Long completedPaymentId = savePayment(2L);
        saveRefund(requestedPaymentId, 1_000L, RefundStatus.REQUESTED);
        saveRefund(completedPaymentId, 1_000L, RefundStatus.COMPLETED);
        entityManager.flush();
        entityManager.clear();

        // when
        List<RefundFindTarget> targets = refundRepository.findTargets(
                RefundStatus.REQUESTED, LocalDateTime.now().plusMinutes(1), 0L, PageRequest.of(0, 20));

        // then
        assertThat(targets).extracting(RefundFindTarget::orderId).containsExactly(1L);
    }

    @Test
    void 커서보다_큰_ID의_환불만_꺼낸다() {
        // given
        saveRefund(savePayment(1L), 1_000L, RefundStatus.REQUESTED);
        saveRefund(savePayment(2L), 1_000L, RefundStatus.REQUESTED);
        entityManager.flush();
        entityManager.clear();
        List<RefundFindTarget> all = refundRepository.findTargets(
                RefundStatus.REQUESTED, LocalDateTime.now().plusMinutes(1), 0L, PageRequest.of(0, 20));

        // when
        List<RefundFindTarget> targets = refundRepository.findTargets(
                RefundStatus.REQUESTED, LocalDateTime.now().plusMinutes(1), all.getFirst().refundId(), PageRequest.of(0, 20));

        // then
        assertThat(targets).extracting(RefundFindTarget::orderId).containsExactly(2L);
    }

    private Long savePayment(Long orderId) {
        Payment payment = Payment.of(orderId, "payment-" + UUID.randomUUID().toString().substring(0, 8), 5_000L);
        entityManager.persist(payment);
        return payment.getId();
    }

    // 금액만 확인하면 돼서 환불 상품 없이 금액을 직접 넣는다
    private void saveRefund(Long paymentId, Long itemsAmount, RefundStatus status) {
        Refund refund = Refund.of(paymentId, RefundType.PARTIAL, "테스트 환불");
        ReflectionTestUtils.setField(refund, "itemsAmount", itemsAmount);
        if (status == RefundStatus.COMPLETED) {
            refund.complete(LocalDateTime.now());
        } else if (status == RefundStatus.FAILED) {
            refund.fail();
        }
        entityManager.persist(refund);
    }


}