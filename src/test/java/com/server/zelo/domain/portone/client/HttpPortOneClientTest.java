package com.server.zelo.domain.portone.client;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.portone.dto.response.PortOnePayment;
import com.server.zelo.domain.portone.config.PortOneProperties;
import com.server.zelo.domain.portone.entity.PortOnePaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpPortOneClientTest {

    private static final String BASE_URL = "https://api.portone.io";
    private static final String PORTONE_ID = "payment-1";
    private static final String PAYMENT_URL = BASE_URL + "/payments/" + PORTONE_ID + "?storeId=store-1";
    private static final String CANCEL_URL = BASE_URL + "/payments/" + PORTONE_ID + "/cancel";
    private static final String CANCEL_REASON = "결제 금액 불일치";
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

    @Test
    void 결제_취소를_요청하면_결제_ID로_만든_멱등성_키와_storeId_사유를_포트원에_보낸다() {
        // given
        server.expect(requestTo(CANCEL_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "cancel-" + PORTONE_ID))
                .andExpect(jsonPath("$.storeId").value("store-1"))
                .andExpect(jsonPath("$.reason").value(CANCEL_REASON))
                .andExpect(jsonPath("$.amount").doesNotExist())
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        // when // then
        assertThatCode(() -> portOneClient.cancelPayment(PORTONE_ID, CANCEL_REASON))
                .doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void 이미_취소된_결제면_예외_없이_성공으로_처리한다() {
        // given
        server.expect(requestTo(CANCEL_URL))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"type":"PAYMENT_ALREADY_CANCELLED","message":"이미 취소된 결제입니다."}
                                """));

        // when // then
        assertThatCode(() -> portOneClient.cancelPayment(PORTONE_ID, CANCEL_REASON))
                .doesNotThrowAnyException();
    }

    @Test
    void 취소할_결제가_없으면_PAYMENT_NOT_FOUND_예외가_발생한다() {
        // given
        server.expect(requestTo(CANCEL_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        // when // then
        assertThatThrownBy(() -> portOneClient.cancelPayment(PORTONE_ID, CANCEL_REASON))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PAYMENT_NOT_FOUND);
    }

    @Test
    void 취소가_거절되면_PG_REQUEST_REJECTED_예외가_발생한다() {
        // given
        server.expect(requestTo(CANCEL_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"type":"INVALID_REQUEST","message":"잘못된 요청입니다."}
                                """));

        // when // then
        assertThatThrownBy(() -> portOneClient.cancelPayment(PORTONE_ID, CANCEL_REASON))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PG_REQUEST_REJECTED);
    }

    @Test
    void 포트원_서버_오류면_재시도_없이_PG_REQUEST_FAILED_예외가_발생한다() {
        // given
        server.expect(requestTo(CANCEL_URL)).andRespond(withServerError());

        // when // then
        assertThatThrownBy(() -> portOneClient.cancelPayment(PORTONE_ID, CANCEL_REASON))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PG_REQUEST_FAILED);
        server.verify();
    }

    @Test
    void 취소_응답이_없으면_PG_TIMEOUT_예외가_발생한다() {
        // given
        server.expect(requestTo(CANCEL_URL))
                .andRespond(request -> {
                    throw new SocketTimeoutException("Read timed out");
                });

        // when // then
        assertThatThrownBy(() -> portOneClient.cancelPayment(PORTONE_ID, CANCEL_REASON))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PG_TIMEOUT);
    }

    @Test
    void 포트원에_연결하지_못하면_취소도_PG_REQUEST_FAILED_예외가_발생한다() {
        // given
        server.expect(requestTo(CANCEL_URL))
                .andRespond(request -> {
                    throw new ConnectException("Connection refused");
                });
        // when // then
        assertThatThrownBy(() -> portOneClient.cancelPayment(PORTONE_ID, CANCEL_REASON))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PG_REQUEST_FAILED);
    }

    @Test
    void 금액을_지정해_취소하면_취소_키와_금액_취소_가능_잔액을_포트원에_보낸다() {
        // given
        server.expect(requestTo(CANCEL_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "refund-7"))
                .andExpect(jsonPath("$.storeId").value("store-1"))
                .andExpect(jsonPath("$.reason").value(CANCEL_REASON))
                .andExpect(jsonPath("$.amount").value(3_000))
                .andExpect(jsonPath("$.currentCancellableAmount").value(10_000))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        // when // then
        assertThatCode(() -> portOneClient.cancelPaymentWithAmount(
                PORTONE_ID, "refund-7", 3_000L, 10_000L, CANCEL_REASON))
                .doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void 금액을_지정한_취소에서_이미_취소된_결제면_PG_REQUEST_REJECTED_예외가_발생한다() {
        // given
        server.expect(requestTo(CANCEL_URL))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"type":"PAYMENT_ALREADY_CANCELLED","message":"이미 취소된 결제입니다."}
                                """));

        // when // then
        assertThatThrownBy(() -> portOneClient.cancelPaymentWithAmount(
                PORTONE_ID, "refund-7", 3_000L, 10_000L, CANCEL_REASON))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PG_REQUEST_REJECTED));
    }

    @ParameterizedTest
    @CsvSource(value = {
            "null, 10000",
            "0, 10000",
            "-1, 10000",
            "3000, null",
            "20000, 10000"
    }, nullValues = "null")
    void 금액을_지정한_취소에서_금액이_올바르지_않으면_포트원에_보내지_않는다(Long amount, Long currentCancellableAmount) {
        // when // then
        assertThatThrownBy(() -> portOneClient.cancelPaymentWithAmount(
                PORTONE_ID, "refund-7", amount, currentCancellableAmount, CANCEL_REASON))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        server.verify();
    }


}