package com.server.zelo.domain.webhook.controller;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.payment.service.PaymentFacade;
import com.server.zelo.domain.webhook.dto.request.WebhookRequest;
import com.server.zelo.domain.webhook.service.PortOneWebhookVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WebhookControllerTest {

    private static final String BODY = """
            {"type":"Transaction.Paid","data":{"paymentId":"payment-uuid-1"}}""";

    @InjectMocks
    private WebhookController webhookController;

    @Mock
    private PaymentFacade paymentFacade;

    @Mock
    private PortOneWebhookVerifier portOneWebhookVerifier;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(webhookController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 서명이_맞으면_결제를_확인하고_200을_반환한다() throws Exception {
        // given
        given(portOneWebhookVerifier.verify("wh-1", "1700000000", "v1,sig", BODY))
                .willReturn(new WebhookRequest("Transaction.Paid", new WebhookRequest.Data("payment-uuid-1")));

        // when // then
        mockMvc.perform(post("/api/payments/webhook")
                        .header("webhook-id", "wh-1")
                        .header("webhook-timestamp", "1700000000")
                        .header("webhook-signature", "v1,sig")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        then(paymentFacade).should().confirmPayment("payment-uuid-1");
    }

    @Test
    void 서명이_틀리면_401을_반환하고_결제를_확인하지_않는다() throws Exception {
        // given
        given(portOneWebhookVerifier.verify(any(), any(), any(), any()))
                .willThrow(new BusinessException(ErrorCode.INVALID_WEBHOOK_SIGNATURE));

        // when // then
        mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_WEBHOOK_SIGNATURE.getCode()));
        then(paymentFacade).should(never()).confirmPayment(anyString());
    }
}