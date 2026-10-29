package com.server.zelo.domain.portone.client;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.portone.dto.request.PortOneBillingKeyRequest;
import com.server.zelo.domain.portone.dto.request.PortOneCancelRequest;
import com.server.zelo.domain.portone.dto.response.*;
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
    private static final String ALREADY_PAID = "ALREADY_PAID";
    private static final String ALREADY_CANCELLED = "PAYMENT_ALREADY_CANCELLED";
    private static final String IDEMPOTENCY_OUTSTANDING = "IDEMPOTENCY_OUTSTANDING_REQUEST";
    private static final String BILLING_KEY_ALREADY_DELETED = "BILLING_KEY_ALREADY_DELETED";


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

    // 빌링키는 이 키만으로 결제할 수 있는 값이라 로그에 남기지 않는다
    @Override
    public PortOneBillingKey getBillingKey(String billingKey) {
        try {
            PortOneBillingKeyResponse response = portOneRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/billing-keys/{billingKey}")
                            .queryParam("storeId", portOneProperties.storeId())
                            .build(billingKey))
                    .retrieve()
                    .body(PortOneBillingKeyResponse.class);

            if (response == null) {
                throw new BusinessException(ErrorCode.PG_REQUEST_FAILED);
            }
            return PortOneBillingKey.from(response);
        } catch (HttpClientErrorException.NotFound e) {
            throw new BusinessException(ErrorCode.INVALID_BILLING_KEY);
        } catch (RestClientResponseException e) {
            log.error("포트원 빌링키 조회 실패 status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.PG_REQUEST_FAILED);
        } catch (ResourceAccessException e) {
            log.warn("포트원 빌링키 조회 응답 없음 cause={}", e.getMostSpecificCause().getClass().getSimpleName());
            if (e.contains(SocketTimeoutException.class)) {
                throw new BusinessException(ErrorCode.PG_TIMEOUT);
            }
            throw new BusinessException(ErrorCode.PG_REQUEST_FAILED);
        }
    }
    // 이미 지워졌거나 없는 빌링키는 삭제 목표를 이룬 것이므로 성공으로 본다 (포트원 삭제 후 DB 삭제가 실패해 다시 시도하는 경우)
    @Override
    public void deleteBillingKey(String billingKey) {
        try {
            portOneRestClient.delete()
                    .uri(uriBuilder -> uriBuilder
                            .path("/billing-keys/{billingKey}")
                            .queryParam("storeId", portOneProperties.storeId())
                            .build(billingKey))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404 || isBillingKeyAlreadyDeleted(e)) {
                return;
            }
            log.error("포트원 빌링키 삭제 거절 status={}", e.getStatusCode());
            throw new BusinessException(ErrorCode.PG_REQUEST_REJECTED);
        } catch (RestClientResponseException e) {
            log.error("포트원 빌링키 삭제 실패 status={}", e.getStatusCode());
            throw new BusinessException(ErrorCode.PG_REQUEST_FAILED);
        } catch (ResourceAccessException e) {
            // 예외 메시지에 요청 URI(빌링키 포함)가 들어가므로 예외 객체 대신 원인 종류만 남긴다
            log.warn("포트원 빌링키 삭제 응답 없음 cause={}", e.getMostSpecificCause().getClass().getSimpleName());
            if (e.contains(SocketTimeoutException.class)) {
                throw new BusinessException(ErrorCode.PG_TIMEOUT);
            }
            throw new BusinessException(ErrorCode.PG_REQUEST_FAILED);
        }
    }

    // 결제 결과는 이후 결제 조회로 다시 확인하므로 응답 본문은 쓰지 않는다
    @Override
    public void payWithBillingKey(String portoneId, String billingKey, String orderName, Long amount) {
        try {
            portOneRestClient.post()
                    .uri("/payments/{paymentId}/billing-key", portoneId)
                    .body(PortOneBillingKeyRequest.of(portOneProperties.storeId(), billingKey, orderName, amount))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            // 이전 요청이 응답 없이 끊겼지만 실제로는 결제된 경우라, 결제 조회로 이어서 반영한다
            if (isErrorType(e, ALREADY_PAID)) {
                log.info("포트원 빌링키 결제 이미 완료 portoneId={}", portoneId);
                return;
            }
            log.warn("포트원 빌링키 결제 거절 portoneId={}, status={}", portoneId, e.getStatusCode());
            throw new BusinessException(ErrorCode.PG_REQUEST_REJECTED,
                    ErrorCode.PG_REQUEST_REJECTED.getMessage(), rejectReason(e));
        } catch (RestClientResponseException e) {
            log.error("포트원 빌링키 결제 실패 portoneId={}, status={}", portoneId, e.getStatusCode());
            throw new BusinessException(ErrorCode.PG_REQUEST_FAILED);
        } catch (ResourceAccessException e) {
            log.warn("포트원 빌링키 결제 응답 없음 portoneId={}, cause={}",
                    portoneId, e.getMostSpecificCause().getClass().getSimpleName());
            if (e.contains(SocketTimeoutException.class)) {
                throw new BusinessException(ErrorCode.PG_TIMEOUT);
            }
            throw new BusinessException(ErrorCode.PG_REQUEST_FAILED);
        }
    }

    @Override
    public void cancelPaymentWithAmount(String portoneId, String cancelKey, Long amount,
                                        Long currentCancellableAmount, String reason) {
        validateCancelAmount(amount, currentCancellableAmount);
        cancel(portoneId, cancelKey,
                PortOneCancelRequest.of(
                        portOneProperties.storeId(),
                        reason,
                        amount,
                        currentCancellableAmount),
                false);

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
            if (alreadyCancelledIsSuccess && isAlreadyCancelled(e)) {
                log.info("포트원 결제 이미 취소됨 portoneId={}", portoneId);
                return;
            }
            if (e.getStatusCode().value() == 404) {
                throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);
            }
            if (isIdempotencyOutstanding(e)) {
                log.warn("포트원 결제 취소 처리 중 portoneId={}", portoneId);
                throw new BusinessException(ErrorCode.PG_REQUEST_FAILED);
            }
            log.error("포트원 결제 취소 거절 portoneId={}, status={}, body={}",
                    portoneId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.PG_REQUEST_REJECTED);
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

    private void validateCancelAmount(Long amount, Long currentCancellableAmount) {
        if (amount == null || amount <= 0 || currentCancellableAmount == null || amount > currentCancellableAmount) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "취소 금액은 1원 이상, 취소 가능 잔액 이하여야 합니다.");
        }
    }

    private boolean isIdempotencyOutstanding(HttpClientErrorException e) {
        try {
            PortOneErrorResponse error = e.getResponseBodyAs(PortOneErrorResponse.class);
            return error != null && IDEMPOTENCY_OUTSTANDING.equals(error.type());
        } catch (RuntimeException parseError) {
            return false;
        }
    }

    private boolean isBillingKeyAlreadyDeleted(HttpClientErrorException e) {
        try {
            PortOneErrorResponse error = e.getResponseBodyAs(PortOneErrorResponse.class);
            return error != null && BILLING_KEY_ALREADY_DELETED.equals(error.type());
        } catch (RuntimeException parseError) {
            return false;
        }
    }

    private boolean isErrorType(HttpClientErrorException e, String type) {
        try {
            PortOneErrorResponse error = e.getResponseBodyAs(PortOneErrorResponse.class);
            return error != null && type.equals(error.type());
        } catch (RuntimeException parseError) {
            return false;
        }
    }

    private String rejectReason(HttpClientErrorException e) {
        try {
            PortOneErrorResponse error = e.getResponseBodyAs(PortOneErrorResponse.class);
            if (error == null) {
                return null;
            }
            if (error.pgMessage() != null && !error.pgMessage().isBlank()) {
                return error.pgMessage();
            }
            return error.message() == null || error.message().isBlank() ? null : error.message();
        } catch (RuntimeException parseError) {
            return null;
        }
    }
}





