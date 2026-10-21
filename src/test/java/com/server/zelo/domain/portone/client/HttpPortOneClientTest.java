package com.server.zelo.domain.portone.client;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.portone.dto.response.PortOnePayment;
import com.server.zelo.domain.portone.dto.response.PortOneProperties;
import com.server.zelo.domain.portone.entity.PortOnePaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpPortOneClientTest {

    private static final String BASE_URL = "https://api.portone.io";
    private static final String PORTONE_ID = "payment-1";
    private static final String PAYMENT_URL = BASE_URL + "/payments/" + PORTONE_ID + "?storeId=store-1";
    private MockRestServiceServer server;
    private HttpPortOneClient portOneClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        PortOneProperties properties = new PortOneProperties(
                BASE_URL, "secret", "store-1",
                Duration.ofSeconds(3), Duration.ofSeconds(5), ZoneId.of("Asia/Seoul"));
        portOneClient = new HttpPortOneClient(builder.build(), properties);
    }

    @Test
    void 포트원_결제가_완료되면_PAID와_결제_금액을_서울_시간으로_반환한다() {
        // given
        server.expect(requestTo(PAYMENT_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"payment-1","status":"PAID","amount":{"total":15000},"paidAt":"2026-10-20T01:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        // when
        PortOnePayment payment = portOneClient.getPayment(PORTONE_ID);

        // then
        assertThat(payment.status()).isEqualTo(PortOnePaymentStatus.PAID);
        assertThat(payment.amount()).isEqualTo(15000L);
        assertThat(payment.paidAt()).isEqualTo(LocalDateTime.of(2026, 10, 20, 10, 0));
        server.verify();
    }

    @Test
    void 알_수_없는_결제_상태면_UNKNOWN으로_변환한다() {
        // given
        server.expect(requestTo(PAYMENT_URL))
                .andRespond(withSuccess("""
                        {"id":"payment-1","status":"NEW_STATUS","amount":{"total":15000}}
                        """, MediaType.APPLICATION_JSON));

        // when
        PortOnePayment payment = portOneClient.getPayment(PORTONE_ID);

        // then
        assertThat(payment.status()).isEqualTo(PortOnePaymentStatus.UNKNOWN);
        assertThat(payment.isPaid()).isFalse();
        assertThat(payment.isFailed()).isFalse();
    }

    @Test
    void 포트원에_결제가_없으면_PAYMENT_NOT_FOUND_예외가_발생한다() {
        // given
        server.expect(requestTo(PAYMENT_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        // when // then
        assertThatThrownBy(() -> portOneClient.getPayment(PORTONE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PAYMENT_NOT_FOUND);
    }

    @Test
    void 포트원_서버_오류면_PG_REQUEST_FAILED_예외가_발생한다() {
        // given
        server.expect(requestTo(PAYMENT_URL)).andRespond(withServerError());

        // when // then
        assertThatThrownBy(() -> portOneClient.getPayment(PORTONE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PG_REQUEST_FAILED);
    }

    @Test
    void 포트원_응답이_없으면_PG_TIMEOUT_예외가_발생한다() {
        // given
        server.expect(requestTo(PAYMENT_URL))
                .andRespond(request -> {
                    throw new SocketTimeoutException("Read timed out");
                });

        // when // then
        assertThatThrownBy(() -> portOneClient.getPayment(PORTONE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PG_TIMEOUT);
    }
}