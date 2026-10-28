package com.server.zelo.domain.portone.client;
import com.server.zelo.domain.portone.dto.response.PortOneBillingKey;
import com.server.zelo.domain.portone.dto.response.PortOnePayment;

public interface PortOneClient {

    PortOnePayment getPayment(String portoneId);

    void cancelPayment(String portoneId, String reason);

    void cancelPaymentWithAmount(String portoneId, String cancelKey, Long amount, Long currentCancellableAmount, String reason);

    PortOneBillingKey getBillingKey(String billingKey);

    void deleteBillingKey(String billingKey);

    void payWithBillingKey(String portoneId, String billingKey, String orderName, Long amount);
}