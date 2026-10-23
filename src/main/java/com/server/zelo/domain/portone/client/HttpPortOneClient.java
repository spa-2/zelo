package com.server.zelo.domain.portone.client;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.portone.dto.request.PortOneCancelRequest;
import com.server.zelo.domain.portone.dto.response.PortOneErrorResponse;
import com.server.zelo.domain.portone.dto.response.PortOnePayment;
import com.server.zelo.domain.portone.dto.response.PortOnePaymentResponse;
import com.server.zelo.domain.portone.config.PortOneProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.net.SocketTimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "zelo.portone.mode", havingValue = "real")
public class HttpPortOneClient implements PortOneClient {

    private final RestClient portOneRestClient;
    private final PortOneProperties portOneProperties;
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String IDEMPOTENCY_KEY_PREFIX = "cancel-";
    private static final String ALREADY_CANCELLED = "PAYMENT_ALREADY_CANCELLED";

    @Override
    public PortOnePayment getPayment(String portoneId) {
        try {
            PortOnePaymentResponse response = portOneRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/payments/{paymentId}")
                            .queryParam("storeId", portOneProperties.storeId())
                            .build(portoneId))
                    .retrieve()
                    .body(PortOnePaymentResponse.class);

            if (response == null) {
                throw new BusinessException(ErrorCode.PG_REQUEST_FAILED);
            }
            return PortOnePayment.from(response, portOneProperties.timeZone());
        } catch (HttpClientErrorException.NotFound e) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);
        } catch (RestClientResponseException e) {
            log.error("포트원 결제 조회 실패 portoneId={}, status={}, body={}",
                    portoneId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.PG_REQUEST_FAILED);
        } catch (ResourceAccessException e) {
            log.warn("포트원 결제 조회 응답 없음 portoneId={}", portoneId, e);
            if (e.contains(SocketTimeoutException.class)) {
                throw new BusinessException(ErrorCode.PG_TIMEOUT);
            }
            throw new BusinessException(ErrorCode.PG_REQUEST_FAILED);
        }
    }
    @Override
    public void cancelPayment(String portoneId, String reason) {
        cancel(portoneId, IDEMPOTENCY_KEY_PREFIX + portoneId,
                PortOneCancelRequest.of(portOneProperties.storeId(), reason), true);
    }

    @Override
    public void cancelPaymentWithAmount(String portoneId, String cancelKey, Long amount,
                                        Long currentCancellableAmount, String reason) {
        cancel(portoneId, cancelKey,
                PortOneCancelRequest.of(portOneProperties.storeId(), reason, amount, currentCancellableAmount), false);
    }

    // 전액 취소는 이미 취소된 결제면 목표를 이룬 것이지만, 금액 지정 취소는 이번 금액이 취소된 게 아니므로 실패로 본다
    private void cancel(String portoneId, String idempotencyKey, PortOneCancelRequest request,
                        boolean alreadyCancelledIsSuccess) {
        try {
            portOneRestClient.post()
                    .uri("/payments/{paymentId}/cancel", portoneId)
                    .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            // 이전 시도가 포트원에서는 성공하고 응답만 끊긴 경우라 환불은 이미 끝난 상태다
            if (alreadyCancelledIsSuccess && isAlreadyCancelled(e)) {
                log.info("포트원 결제 이미 취소됨 portoneId={}", portoneId);
                return;
            }
            if (e.getStatusCode().value() == 404) {
                throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);
            }
            log.error("포트원 결제 취소 거절 portoneId={}, status={}, body={}",
                    portoneId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.PG_REQUEST_FAILED);
        } catch (RestClientResponseException e) {
            log.error("포트원 결제 취소 실패 portoneId={}, status={}, body={}",
                    portoneId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.PG_REQUEST_FAILED);
        } catch (ResourceAccessException e) {
            throw toAccessError("취소", portoneId, e);
        }
    }
    private boolean isAlreadyCancelled(HttpClientErrorException e) {
        try {
            PortOneErrorResponse error = e.getResponseBodyAs(PortOneErrorResponse.class);
            return error != null && ALREADY_CANCELLED.equals(error.type());
        } catch (RuntimeException parseError) {
            // 에러 본문이 JSON이 아니면 이미 취소된 경우로 볼 근거가 없다
            return false;
        }
    }

    // ResourceAccessException은 연결 거부·DNS 실패도 포함하므로 실제 타임아웃만 PG_TIMEOUT으로 구분한다
    private BusinessException toAccessError(String action, String portoneId, ResourceAccessException e) {
        if (e.contains(SocketTimeoutException.class)) {
            log.warn("포트원 결제 {} 타임아웃 portoneId={}", action, portoneId, e);
            return new BusinessException(ErrorCode.PG_TIMEOUT);
        }
        log.error("포트원 결제 {} 연결 실패 portoneId={}", action, portoneId, e);
        return new BusinessException(ErrorCode.PG_REQUEST_FAILED);
    }
}





