package com.server.zelo.domain.webhook.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.payment.service.PaymentFacade;
import com.server.zelo.domain.webhook.dto.request.WebhookRequest;
import com.server.zelo.domain.webhook.entity.WebhookEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    private WebhookCommandService webhookCommandService;

    @BeforeEach
    void setUp() {
        webhookCommandService = new WebhookCommandService(paymentFacade, webhookEventService,
                TransactionOperations.withoutTransaction());
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

    private WebhookEvent receivedEvent() {
        WebhookEvent event = WebhookEvent.of(WEBHOOK_ID, PORTONE_ID, "Transaction.Paid", BODY);
        ReflectionTestUtils.setField(event, "id", 1L);
        return event;
    }
}