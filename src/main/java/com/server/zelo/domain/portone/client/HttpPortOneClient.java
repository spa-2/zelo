package com.server.zelo.domain.portone.client;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.portone.dto.response.PortOnePayment;
import com.server.zelo.domain.portone.dto.response.PortOnePaymentResponse;
import com.server.zelo.domain.portone.dto.response.PortOneProperties;
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
    // TODO(portone): 결제 취소 연동 이슈에서 구현한다. 그 전까지 금액 불일치 환불이 조용히 넘어가지 않도록 예외를 던진다
    @Override
    public void cancelPayment(String portoneId, String reason) {
        throw new UnsupportedOperationException("포트원 결제 취소는 아직 연동되지 않았습니다.");
    }
}




