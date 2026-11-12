package com.server.zelo.domain.portone.dto.response;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.payment.entity.PaymentMethodType;

import java.util.List;

public record PortOneBillingKey(
        String billingKey,
        PaymentMethodType type,
        String displayName,
        String customerId
) {

    private static final String ISSUED = "ISSUED";
    private static final String CARD = "BillingKeyPaymentMethodCard";
    private static final String DEFAULT_CARD_NAME = "카드";
    private static final int SUFFIX_LENGTH = 4;
    private static final int DISPLAY_NAME_MAX_LENGTH = 50;
    private static final String CUSTOMER_ID_PREFIX = "user-";

    public static PortOneBillingKey of(String billingKey, PaymentMethodType type, String displayName, String customerId) {
        return new PortOneBillingKey(billingKey, type, displayName, customerId);
    }

    // 삭제됐거나 카드가 아닌 빌링키는 받지 않는다 (계좌는 PG 계약 필요)
    public static PortOneBillingKey from(PortOneBillingKeyResponse response) {
        List<PortOneBillingKeyResponse.Method> methods = response.methods();
        if (!ISSUED.equals(response.status()) || methods == null || methods.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_BILLING_KEY);
        }
        PortOneBillingKeyResponse.Method method = methods.get(0);
        if (!CARD.equals(method.type()) || method.card() == null) {
            throw new BusinessException(ErrorCode.INVALID_BILLING_KEY);
        }
        String customerId = response.customer() == null ? null : response.customer().id();
        String name = displayName(method.card().name(), method.card().number());
        return of(response.billingKey(), PaymentMethodType.CARD, name, customerId);
    }

    // 토스페이먼츠는 고객 ID를 2자 이상만 받아서 "user-{id}"로 발급한다.
    public boolean isOwnedBy(Long userId) {
        String id = String.valueOf(userId);
        return (CUSTOMER_ID_PREFIX + id).equals(customerId) || id.equals(customerId);
    }

    // 예: 신한카드 1234. 카드번호는 끝 4자리만 남긴다
    private static String displayName(String cardName, String number) {
        String prefix = cardName == null || cardName.isBlank() ? DEFAULT_CARD_NAME : cardName;
        String displayName = number == null || number.length() < SUFFIX_LENGTH
                ? prefix
                : prefix + " " + number.substring(number.length() - SUFFIX_LENGTH);
        return displayName.length() > DISPLAY_NAME_MAX_LENGTH
                ? displayName.substring(0, DISPLAY_NAME_MAX_LENGTH)
                : displayName;
    }
}