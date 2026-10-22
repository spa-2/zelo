package com.server.zelo.domain.webhook.controller;

import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.payment.facade.PaymentFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WebhookControllerTest {

    @InjectMocks
    private WebhookController webhookController;

    @Mock
    private PaymentFacade paymentFacade;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(webhookController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 포트원_웹훅을_받으면_결제를_확인한다() throws Exception {
        // given
        String body = """
                {
                  "type": "Transaction.Paid",
                  "timestamp": "2026-10-19T12:00:00Z",
                  "data": {
                    "paymentId": "payment-uuid-1",
                    "storeId": "store-id",
                    "transactionId": "transaction-id"
                  }
                }
                """;

        // when // then
        mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        then(paymentFacade).should().confirmPayment("payment-uuid-1");
    }

    @Test
    void 웹훅에_결제_ID가_없으면_400을_반환한다() throws Exception {
        // given
        String body = """
                {
                  "type": "Transaction.Paid",
                  "data": {}
                }
                """;

        // when // then
        mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()));
        then(paymentFacade).should(never()).confirmPayment(anyString());
    }
}