package com.server.zelo.domain.refund.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.inventory.entity.Inventory;
import com.server.zelo.domain.inventory.repository.InventoryRepository;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.repository.PaymentRepository;
import com.server.zelo.domain.portone.client.PortOneClient;
import com.server.zelo.domain.refund.dto.request.CreateRefundRequest;
import com.server.zelo.domain.refund.entity.Refund;
import com.server.zelo.domain.refund.entity.RefundStatus;
import com.server.zelo.domain.refund.repository.RefundRepository;
import com.server.zelo.support.Concurrency;
import com.server.zelo.support.Concurrency.Result;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@ActiveProfiles("test")
@Import(Concurrency.class)
class RefundConcurrencyTest {

    private static final int THREAD_COUNT = 10;

    @MockitoSpyBean
    private PortOneClient portOneClient;

    @Autowired
    private RefundFacade refundFacade;

    @Autowired
    private Concurrency fixture;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    private Concurrency.SavedOrder savedOrder;

    @AfterEach
    void tearDown() {
        fixture.delete(savedOrder);
    }

    // 환불 버튼 연타나 여러 탭에서 같은 주문을 동시에 환불하는 상황
    @Test
    void 같은_주문에_환불을_동시에_요청해도_포트원_취소는_한_번만_나간다() throws InterruptedException {
        // given
        savedOrder = fixture.savePaidOrder();
        CreateRefundRequest request = new CreateRefundRequest("단순 변심", null);

        // when
        Result result = fixture.runConcurrently(THREAD_COUNT,
                () -> refundFacade.createRefund(savedOrder.userId(), savedOrder.orderId(), request));

        // then
        Payment payment = paymentRepository.findByPortoneId(savedOrder.portoneId()).orElseThrow();
        List<Refund> refunds = refundRepository.findAll().stream()
                .filter(refund -> refund.getPaymentId().equals(payment.getId()))
                .toList();
        Inventory inventory = inventoryRepository.findById(savedOrder.stock().inventoryId()).orElseThrow();
        assertThat(result.successCount()).isEqualTo(1);
        // 늦게 온 요청은 락을 못 얻었거나, 락을 얻은 뒤 이미 환불된 결제라 거절돼야 한다. 그 밖의 오류는 실패로 본다
        assertThat(result.errorCodes())
                .hasSize(THREAD_COUNT - 1)
                .isSubsetOf(ErrorCode.TIMEOUT_EXCEPTION, ErrorCode.PAYMENT_NOT_REFUNDABLE, ErrorCode.REFUND_IN_PROGRESS);
        then(portOneClient).should(times(1))
                .cancelPaymentWithAmount(eq(savedOrder.portoneId()), any(), any(), any(), any());
        assertThat(refunds).singleElement()
                .extracting(Refund::getStatus)
                .isEqualTo(RefundStatus.COMPLETED);
        assertThat(inventory.getStockQuantity()).isEqualTo(10);
    }
}