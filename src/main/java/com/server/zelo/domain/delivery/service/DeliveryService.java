package com.server.zelo.domain.delivery.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.delivery.entity.Delivery;
import com.server.zelo.domain.delivery.entity.DeliveryStatus;
import com.server.zelo.domain.delivery.repository.DeliveryRepository;
import com.server.zelo.domain.order.entity.ShippingAddress;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional
@RequiredArgsConstructor
public class DeliveryService {

    // 한 트랜잭션에서 처리할 최대 건수. 밀린 배송은 스케줄러가 이 단위로 반복해서 처리한다
    public static final int BATCH_SIZE = 100;

    private final DeliveryRepository deliveryRepository;

    // 배송지는 결제 완료 시점의 기본 배송지를 복사해 둔다. 이후 사용자가 배송지를 바꾸거나 지워도 이미 접수된 배송은 바뀌지 않는다
    // 주문할 때 복사해 둔 배송지로 배송을 만든다. 주문 후 사용자가 기본 배송지를 바꾸거나 지워도 주문 때 주소로 배송된다
    public Delivery createDelivery(Long orderId, ShippingAddress address) {
        return deliveryRepository.save(Delivery.of(orderId, address.getRecipientName(),
                address.getRecipientPhone(), address.getZipCode(), address.getAddress(), null));
    }

    // 배송 준비가 일정 시간 지난 배송을 출고 처리한다. 변경은 변경 감지로 반영된다
    public int shipReadyDeliveries(LocalDateTime readyBefore, LocalDateTime now) {
        List<Delivery> deliveries = findProgressTargets(DeliveryStatus.READY, readyBefore);
        deliveries.forEach(delivery -> delivery.ship(now));
        return deliveries.size();
    }

    // 출고 후 일정 시간이 지난 배송을 배송 완료 처리한다
    public int completeShippingDeliveries(LocalDateTime shippingBefore, LocalDateTime now) {
        List<Delivery> deliveries = findProgressTargets(DeliveryStatus.SHIPPING, shippingBefore);
        deliveries.forEach(delivery -> delivery.complete(now));
        return deliveries.size();
    }

    // 환불하는 사이 스케줄러가 출고하지 않도록 포트원 환불 전에 배송을 보류한다
    public void holdForRefund(Long orderId) {
        getDelivery(orderId).hold();
    }

    // 부분 환불이 끝났거나 환불이 실패하면 배송은 취소하지 않고 보류만 풀어 원래대로 출고되게 한다
    public void releaseHold(Long orderId) {
        getDelivery(orderId).release();
    }

    // 전액 환불이 끝나면 보낼 상품이 없으므로 배송을 취소한다
    public void cancelDelivery(Long orderId) {
        Delivery delivery = getDelivery(orderId);
        delivery.release();
        delivery.cancel(LocalDateTime.now());
    }

    private Delivery getDelivery(Long orderId) {
        return deliveryRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DELIVERY_NOT_FOUND));
    }

    private List<Delivery> findProgressTargets(DeliveryStatus status, LocalDateTime before) {
        return deliveryRepository.findByStatusAndOnHoldFalseAndUpdatedAtBeforeOrderByIdAsc(
                status, before, Limit.of(BATCH_SIZE));
    }
}