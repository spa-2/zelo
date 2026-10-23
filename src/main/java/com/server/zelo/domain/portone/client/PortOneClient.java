package com.server.zelo.domain.portone.client;

import com.server.zelo.domain.portone.dto.response.PortOnePayment;

// TODO(portone): 실제 포트원 V2 API(결제 단건 조회, 결제 취소)를 호출하는 구현체를 추가한다
public interface PortOneClient {

    PortOnePayment getPayment(String portoneId);

    void cancelPayment(String portoneId, String reason);

    void cancelPaymentWithAmount(String portoneId, String cancelKey, Long amount, Long currentCancellableAmount, String reason);
}