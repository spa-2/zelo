package com.server.zelo.domain.payment.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.payment.entity.PaymentMethod;
import com.server.zelo.domain.payment.entity.PaymentMethodType;
import com.server.zelo.domain.payment.repository.PaymentMethodRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class PaymentMethodService {

    private final PaymentMethodRepository paymentMethodRepository;

    public PaymentMethod createPaymentMethod(Long userId, PaymentMethodType type,
                                             String billingKey, String displayName) {
        if (paymentMethodRepository.existsByBillingKey(billingKey)) {
            throw new BusinessException(ErrorCode.PAYMENT_METHOD_ALREADY_REGISTERED);
        }
        try {
            return paymentMethodRepository.save(PaymentMethod.of(userId, type, billingKey, displayName));
        } catch (DataIntegrityViolationException e) {
            // null·길이는 엔티티에서 먼저 막으므로 여기서 남는 위반은 동시에 들어온 같은 빌링키뿐이다
            throw new BusinessException(ErrorCode.PAYMENT_METHOD_ALREADY_REGISTERED);
        }
    }

    // 다른 사용자의 결제 수단도 없는 것과 같은 예외로 응답해 존재 여부를 드러내지 않는다
    @Transactional(readOnly = true)
    public PaymentMethod getOwnedMethod(Long paymentMethodId, Long userId) {
        return paymentMethodRepository.findByIdAndUserId(paymentMethodId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_METHOD_NOT_FOUND));
    }
}