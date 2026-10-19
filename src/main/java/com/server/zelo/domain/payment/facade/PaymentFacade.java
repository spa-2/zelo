package com.server.zelo.domain.payment.facade;

import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.payment.service.PaymentCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentFacade {

    private final LockDistributeService lockDistributeService;
    private final PaymentCommandService paymentCommandService;

    // 웹훅 재전송, 결제 완료 확인 요청, 스케줄러가 같은 결제를 동시에 처리하지 않도록 결제 단위로 락을 건다
    public void confirmPayment(String portoneId) {
        lockDistributeService.execute(LockKey.confirmPayment(portoneId),
                () -> paymentCommandService.confirmPayment(portoneId));
    }
}