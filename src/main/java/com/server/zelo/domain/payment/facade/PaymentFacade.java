package com.server.zelo.domain.payment.facade;

import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.payment.dto.response.CreatePaymentResponse;
import com.server.zelo.domain.payment.service.PaymentCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentFacade {

    private final LockDistributeService lockDistributeService;
    private final PaymentCommandService paymentCommandService;

    // @Transactional은 Command에 있고, 락이 커밋 이후에 풀리도록 Facade에서는 락만 검
    public CreatePaymentResponse createPayment(Long userId, Long orderId) {
        return lockDistributeService.execute(LockKey.createPayment(orderId),
                () -> paymentCommandService.createPayment(userId, orderId));
    }
}
