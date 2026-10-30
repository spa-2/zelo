package com.server.zelo.domain.portone.client;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.payment.entity.PaymentMethodType;
import com.server.zelo.domain.portone.dto.response.PortOneBillingKey;
import com.server.zelo.domain.portone.dto.response.PortOnePayment;
import com.server.zelo.domain.portone.config.PortOneProperties;
import com.server.zelo.domain.portone.entity.PortOnePaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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

@ExtendWith(OutputCaptureExtension.class)
class HttpPortOneClientTest {

    private static final String BASE_URL = "https://api.portone.io";
    private static final String PORTONE_ID = "payment-1";
    private static final String PAYMENT_URL = BASE_URL + "/payments/" + PORTONE_ID + "?storeId=store-1";
    private static final String CANCEL_URL = BASE_URL + "/payments/" + PORTONE_ID + "/cancel";
    private static final String CANCEL_REASON = "결제 금액 불일치";
    private static final String BILLING_KEY = "billing-key-1";
    private static final String BILLING_KEY_URL = BASE_URL + "/billing-keys/" + BILLING_KEY + "?storeId=store-1";
    private static final String BILLING_PAYMENT_URL = BASE_URL + "/payments/" + PORTONE_ID + "/billing-key";
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
                        {"id":"payment-1","status":"PAID","amount":{"total":15000},"paidAt":"2026-10-20T01:00:00Z",
                         "method":{"type":"PaymentMethodCard","card":{"name":"신한카드"}}}
                        """, MediaType.APPLICATION_JSON));

        // when
        PortOnePayment payment = portOneClient.getPayment(PORTONE_ID);

        // then
        assertThat(payment.status()).isEqualTo(PortOnePaymentStatus.PAID);
        assertThat(payment.amount()).isEqualTo(15000L);
        assertThat(payment.paidAt()).isEqualTo(LocalDateTime.of(2026, 10, 20, 10, 0));
        assertThat(payment.methodName()).isEqualTo("신한카드");
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

    @Test
    void 같은_멱등_키의_취소가_처리_중이면_PG_REQUEST_FAILED_예외가_발생한다() {
        // given
        server.expect(requestTo(CANCEL_URL))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"type":"IDEMPOTENCY_OUTSTANDING_REQUEST","message":"처리 중인 요청입니다."}
                                """));

        // when // then
        assertThatThrownBy(() -> portOneClient.cancelPaymentWithAmount(
                PORTONE_ID, "refund-cancel-000000000007", 3_000L, 10_000L, CANCEL_REASON))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PG_REQUEST_FAILED));
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

    @Test
    void 발급된_카드_빌링키를_조회하면_종류_표시_이름_발급_고객을_반환한다() {
        // given
        server.expect(requestTo(BILLING_KEY_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":"ISSUED","billingKey":"billing-key-1",
                         "methods":[{"type":"BillingKeyPaymentMethodCard","card":{"name":"신한카드","number":"53275000****1234"}}],
                         "customer":{"id":"1"}}
                        """, MediaType.APPLICATION_JSON));

        // when
        PortOneBillingKey billingKey = portOneClient.getBillingKey(BILLING_KEY);

        // then
        assertThat(billingKey.type()).isEqualTo(PaymentMethodType.CARD);
        assertThat(billingKey.displayName()).isEqualTo("신한카드 1234");
        assertThat(billingKey.isOwnedBy(1L)).isTrue();
        server.verify();
    }

    @Test
    void 포트원에_빌링키가_없으면_INVALID_BILLING_KEY_예외가_발생한다() {
        // given
        server.expect(requestTo(BILLING_KEY_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        // when // then
        assertThatThrownBy(() -> portOneClient.getBillingKey(BILLING_KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_BILLING_KEY);
    }

    @Test
    void 빌링키_조회_응답이_비어_있으면_PG_REQUEST_FAILED_예외가_발생한다() {
        // given
        server.expect(requestTo(BILLING_KEY_URL)).andRespond(withSuccess());

        // when // then
        assertThatThrownBy(() -> portOneClient.getBillingKey(BILLING_KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PG_REQUEST_FAILED);
    }

    @Test
    void 빌링키_조회_중_포트원_서버_오류면_PG_REQUEST_FAILED_예외가_발생한다() {
        // given
        server.expect(requestTo(BILLING_KEY_URL)).andRespond(withServerError());

        // when // then
        assertThatThrownBy(() -> portOneClient.getBillingKey(BILLING_KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PG_REQUEST_FAILED);
    }

    @Test
    void 빌링키_조회_응답이_없으면_PG_TIMEOUT_예외가_발생하고_로그에_빌링키를_남기지_않는다(CapturedOutput output) {
        // given
        server.expect(requestTo(BILLING_KEY_URL))
                .andRespond(request -> {
                    throw new SocketTimeoutException("Read timed out");
                });

        // when // then
        assertThatThrownBy(() -> portOneClient.getBillingKey(BILLING_KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PG_TIMEOUT);
        assertThat(output).contains("포트원 빌링키 조회 응답 없음");
        assertThat(output).doesNotContain(BILLING_KEY);
    }

    @Test
    void 빌링키_조회_중_연결에_실패하면_PG_REQUEST_FAILED_예외가_발생하고_로그에_빌링키를_남기지_않는다(CapturedOutput output) {
        // given
        server.expect(requestTo(BILLING_KEY_URL))
                .andRespond(request -> {
                    throw new ConnectException("Connection refused");
                });

        // when // then
        assertThatThrownBy(() -> portOneClient.getBillingKey(BILLING_KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PG_REQUEST_FAILED);
        assertThat(output).doesNotContain(BILLING_KEY);
    }

    @Test
    void 빌링키를_삭제하면_포트원에_DELETE_요청을_보낸다() {
        // given
        server.expect(requestTo(BILLING_KEY_URL))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess());

        // when // then
        assertThatCode(() -> portOneClient.deleteBillingKey(BILLING_KEY)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void 포트원에_없는_빌링키를_삭제하면_성공으로_본다() {
        // given
        server.expect(requestTo(BILLING_KEY_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        // when // then
        assertThatCode(() -> portOneClient.deleteBillingKey(BILLING_KEY)).doesNotThrowAnyException();
    }

    @Test
    void 이미_삭제된_빌링키를_삭제하면_성공으로_본다() {
        // given
        server.expect(requestTo(BILLING_KEY_URL))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"type":"BILLING_KEY_ALREADY_DELETED","message":"이미 삭제된 빌링키"}
                                """));

        // when // then
        assertThatCode(() -> portOneClient.deleteBillingKey(BILLING_KEY)).doesNotThrowAnyException();
    }

    @Test
    void 포트원이_빌링키_삭제를_거절하면_PG_REQUEST_REJECTED_예외가_발생한다() {
        // given
        server.expect(requestTo(BILLING_KEY_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        // when // then
        assertThatThrownBy(() -> portOneClient.deleteBillingKey(BILLING_KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PG_REQUEST_REJECTED);
    }

    @Test
    void 빌링키_삭제_중_포트원_서버_오류면_PG_REQUEST_FAILED_예외가_발생한다() {
        // given
        server.expect(requestTo(BILLING_KEY_URL)).andRespond(withServerError());

        // when // then
        assertThatThrownBy(() -> portOneClient.deleteBillingKey(BILLING_KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PG_REQUEST_FAILED);
    }

    @Test
    void 빌링키_삭제_응답이_없으면_PG_TIMEOUT_예외가_발생하고_로그에_빌링키를_남기지_않는다(CapturedOutput output) {
        // given
        server.expect(requestTo(BILLING_KEY_URL))
                .andRespond(request -> {
                    throw new SocketTimeoutException("Read timed out");
                });

        // when // then
        assertThatThrownBy(() -> portOneClient.deleteBillingKey(BILLING_KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PG_TIMEOUT);
        assertThat(output).doesNotContain(BILLING_KEY);
    }

    @Test
    void 빌링키_결제를_요청하면_상점_ID_빌링키_금액을_보낸다() {
        // given
        server.expect(requestTo(BILLING_PAYMENT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.storeId").value("store-1"))
                .andExpect(jsonPath("$.billingKey").value(BILLING_KEY))
                .andExpect(jsonPath("$.orderName").value("티셔츠 외 1건"))
                .andExpect(jsonPath("$.amount.total").value(15000))
                .andExpect(jsonPath("$.currency").value("KRW"))
                .andRespond(withSuccess());

        // when // then
        assertThatCode(() -> portOneClient.payWithBillingKey(PORTONE_ID, BILLING_KEY, "티셔츠 외 1건", 15000L))
                .doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void 이미_결제된_건이면_빌링키_결제를_성공으로_본다() {
        // given
        server.expect(requestTo(BILLING_PAYMENT_URL))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"type":"ALREADY_PAID","message":"이미 결제됨"}
                                """));

        // when // then
        assertThatCode(() -> portOneClient.payWithBillingKey(PORTONE_ID, BILLING_KEY, "티셔츠 외 1건", 15000L))
                .doesNotThrowAnyException();
    }

    @Test
    void 카드사가_빌링키_결제를_거절하면_PG_REQUEST_REJECTED_예외가_발생한다() {
        // given
        server.expect(requestTo(BILLING_PAYMENT_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"type":"PG_PROVIDER","message":"한도 초과"}
                                """));

        // when // then
        assertThatThrownBy(() -> portOneClient.payWithBillingKey(PORTONE_ID, BILLING_KEY, "티셔츠 외 1건", 15000L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PG_REQUEST_REJECTED);
    }

    @Test
    void 빌링키_결제_중_포트원_서버_오류면_PG_REQUEST_FAILED_예외가_발생한다() {
        // given
        server.expect(requestTo(BILLING_PAYMENT_URL)).andRespond(withServerError());

        // when // then
        assertThatThrownBy(() -> portOneClient.payWithBillingKey(PORTONE_ID, BILLING_KEY, "티셔츠 외 1건", 15000L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PG_REQUEST_FAILED);
    }

    @Test
    void 빌링키_결제_응답이_없으면_PG_TIMEOUT_예외가_발생하고_로그에_빌링키를_남기지_않는다(CapturedOutput output) {
        // given
        server.expect(requestTo(BILLING_PAYMENT_URL))
                .andRespond(request -> {
                    throw new SocketTimeoutException("Read timed out");
                });

        // when // then
        assertThatThrownBy(() -> portOneClient.payWithBillingKey(PORTONE_ID, BILLING_KEY, "티셔츠 외 1건", 15000L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PG_TIMEOUT);
        assertThat(output).doesNotContain(BILLING_KEY);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"type\":\"PG_PROVIDER\",\"message\":\"포트원 사유\",\"pgMessage\":\"한도 초과\"} | 한도 초과",
            "{\"type\":\"PG_PROVIDER\",\"message\":\"포트원 사유\",\"pgMessage\":\" \"}       | 포트원 사유",
            "{\"type\":\"PG_PROVIDER\",\"message\":\"포트원 사유\"}                           | 포트원 사유",
            "{\"type\":\"PG_PROVIDER\",\"message\":\"\",\"pgMessage\":\"\"}                   | ",
            "not-json                                                                     | "
    })
    void 빌링키_결제가_거절되면_PG_문구를_우선해_거절_사유를_예외에_담는다(String body, String expectedReason) {
        // given
        server.expect(requestTo(BILLING_PAYMENT_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body));

        // when // then
        assertThatThrownBy(() -> portOneClient.payWithBillingKey(PORTONE_ID, BILLING_KEY, "티셔츠 외 1건", 15000L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PG_REQUEST_REJECTED);
                    assertThat(exception.getData()).isEqualTo(expectedReason);
                });
    }

    @Test
    void PG사가_빌링키_결제를_거절하면_502여도_거절_사유와_함께_PG_REQUEST_REJECTED_예외가_발생한다() {
        // given
        server.expect(requestTo(BILLING_PAYMENT_URL))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"type":"PG_PROVIDER","message":"PG사 오류","pgCode":"1234","pgMessage":"잔액이 부족합니다"}
                                """));

        // when // then
        assertThatThrownBy(() -> portOneClient.payWithBillingKey(PORTONE_ID, BILLING_KEY, "티셔츠 외 1건", 15000L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PG_REQUEST_REJECTED);
                    assertThat(exception.getData()).isEqualTo("잔액이 부족합니다");
                });
    }

    @Test
    void PG_거절이_아닌_502는_결과를_알_수_없어_PG_REQUEST_FAILED_예외가_발생한다() {
        // given
        server.expect(requestTo(BILLING_PAYMENT_URL))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>Bad Gateway</html>"));

        // when // then
        assertThatThrownBy(() -> portOneClient.payWithBillingKey(PORTONE_ID, BILLING_KEY, "티셔츠 외 1건", 15000L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PG_REQUEST_FAILED);
    }
}