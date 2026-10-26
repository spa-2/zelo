package com.server.zelo.domain.webhook.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.service.PaymentFacade;
import com.server.zelo.domain.payment.service.PaymentService;
import com.server.zelo.domain.refund.service.RefundFacade;
import com.server.zelo.domain.webhook.dto.request.WebhookRequest;
import com.server.zelo.domain.webhook.entity.WebhookEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class WebhookCommandServiceTest {

    private static final String WEBHOOK_ID = "wh-1";
    private static final String PORTONE_ID = "payment-uuid-1";
    private static final String BODY = "{}";
    private static final WebhookRequest REQUEST =
            new WebhookRequest("Transaction.Paid", new WebhookRequest.Data(PORTONE_ID));

    @Mock
    private PaymentFacade paymentFacade;

    @Mock
    private WebhookEventService webhookEventService;

    @Mock
    private RefundFacade refundFacade;

    @Mock
    private PaymentService paymentService;

    private WebhookCommandService webhookCommandService;

    @BeforeEach
    void setUp() {
        webhookCommandService = new WebhookCommandService(refundFacade, paymentFacade, paymentService,
                webhookEventService, TransactionOperations.withoutTransaction());
    }

    @Test
    void 처음_받은_웹훅이면_결제를_확인하고_처리_완료로_남긴다() {
        // given
        given(webhookEventService.receive(WEBHOOK_ID, REQUEST, BODY)).willReturn(receivedEvent());

        // when
        webhookCommandService.handle(WEBHOOK_ID, REQUEST, BODY);

        // then
        then(paymentFacade).should().confirmPayment(PORTONE_ID);
        then(webhookEventService).should().process(eq(1L), any(LocalDateTime.class));
    }

    @Test
    void 이미_처리한_웹훅이면_결제를_다시_확인하지_않는다() {
        // given
        WebhookEvent event = receivedEvent();
        event.process(LocalDateTime.now());
        given(webhookEventService.receive(WEBHOOK_ID, REQUEST, BODY)).willReturn(event);

        // when
        webhookCommandService.handle(WEBHOOK_ID, REQUEST, BODY);

        // then
        then(paymentFacade).should(never()).confirmPayment(anyString());
        then(webhookEventService).should(never()).process(any(), any());
    }

    @Test
    void 결제_확인이_실패하면_실패로_남기고_예외를_다시_던진다() {
        // given
        given(webhookEventService.receive(WEBHOOK_ID, REQUEST, BODY)).willReturn(receivedEvent());
        willThrow(new BusinessException(ErrorCode.PG_TIMEOUT)).given(paymentFacade).confirmPayment(PORTONE_ID);

        // when // then
        assertThatThrownBy(() -> webhookCommandService.handle(WEBHOOK_ID, REQUEST, BODY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PG_TIMEOUT);
        then(webhookEventService).should().fail(eq(1L), any());
        then(webhookEventService).should(never()).process(any(), any());
    }

    @Test
    void 같은_웹훅이_동시에_저장돼_중복_키_예외가_나면_다시_조회해_처리한다() {
        // given
        given(webhookEventService.receive(WEBHOOK_ID, REQUEST, BODY))
                .willThrow(new DataIntegrityViolationException("duplicate webhook_id"))
                .willReturn(receivedEvent());

        // when
        webhookCommandService.handle(WEBHOOK_ID, REQUEST, BODY);

        // then
        then(webhookEventService).should(times(2)).receive(WEBHOOK_ID, REQUEST, BODY);
        then(paymentFacade).should().confirmPayment(PORTONE_ID);
        then(webhookEventService).should().process(eq(1L), any(LocalDateTime.class));
    }

    @Test
    void 취소_웹훅이면_결제_확인_뒤_취소를_동기화한다() {
        // given
        WebhookRequest cancelled = new WebhookRequest("Transaction.Cancelled", new WebhookRequest.Data(PORTONE_ID));
        given(webhookEventService.receive(WEBHOOK_ID, cancelled, BODY)).willReturn(receivedEvent());
        given(paymentService.getPayment(PORTONE_ID)).willReturn(Payment.of(10L, PORTONE_ID, 32_000L));

        // when
        webhookCommandService.handle(WEBHOOK_ID, cancelled, BODY);

        // then
        then(paymentFacade).should().confirmPayment(PORTONE_ID);
        then(refundFacade).should().syncCancellation(10L, PORTONE_ID);
        then(webhookEventService).should().process(eq(1L), any(LocalDateTime.class));
    }

    @Test
    void 처리하지_않는_웹훅_종류면_무시로_기록하고_결제를_확인하지_않는다() {
        // given
        WebhookRequest ready = new WebhookRequest("Transaction.Ready", new WebhookRequest.Data(PORTONE_ID));
        given(webhookEventService.receive(WEBHOOK_ID, ready, BODY)).willReturn(receivedEvent());

        // when
        webhookCommandService.handle(WEBHOOK_ID, ready, BODY);

        // then
        then(webhookEventService).should().ignore(1L);
        then(paymentFacade).shouldHaveNoInteractions();
        then(refundFacade).shouldHaveNoInteractions();
    }

    private WebhookEvent receivedEvent() {
        WebhookEvent event = WebhookEvent.of(WEBHOOK_ID, PORTONE_ID, "Transaction.Paid", BODY);
        ReflectionTestUtils.setField(event, "id", 1L);
        return event;
    }
}