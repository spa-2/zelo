package com.server.zelo.domain.payment.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.payment.entity.PaymentMethod;
import com.server.zelo.domain.portone.client.PortOneClient;
import com.server.zelo.domain.portone.dto.response.PortOneBillingKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentMethodCommandService {

    private final PortOneClient portOneClient;
    private final PaymentMethodService paymentMethodService;

    // 포트원 조회는 외부 HTTP 호출이라 DB 커넥션을 붙잡지 않도록 트랜잭션 밖에서 하고, 저장만 PaymentMethodService의 트랜잭션으로 한다
    public PaymentMethod createPaymentMethod(Long userId, String billingKey) {
        PortOneBillingKey pgBillingKey = portOneClient.getBillingKey(billingKey);
        // 다른 사용자가 발급한 빌링키를 등록하면 그 사람 카드로 결제할 수 있으므로 발급 고객을 확인한다
        if (!pgBillingKey.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.INVALID_BILLING_KEY);
        }
        return paymentMethodService.createPaymentMethod(
                userId, pgBillingKey.type(), billingKey, pgBillingKey.displayName());
    }

    // 우리 DB만 지우면 포트원에 결제 가능한 빌링키가 남으므로 포트원을 먼저 지우고, 실패하면 DB를 남겨 다시 시도할 수 있게 한다
    public void deletePaymentMethod(Long userId, Long paymentMethodId) {
        PaymentMethod paymentMethod = paymentMethodService.getOwnedMethod(paymentMethodId, userId);
        portOneClient.deleteBillingKey(paymentMethod.getBillingKey());
        paymentMethodService.deletePaymentMethod(paymentMethod.getId());
    }
}