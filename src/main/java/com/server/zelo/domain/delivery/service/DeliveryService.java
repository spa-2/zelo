package com.server.zelo.domain.delivery.service;

import com.server.zelo.domain.delivery.entity.Delivery;
import com.server.zelo.domain.delivery.repository.DeliveryRepository;
import com.server.zelo.domain.user.entity.UserAddress;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class DeliveryService {

    private final DeliveryRepository deliveryRepository;

    // 배송지는 주문 시점 주소로 복사해 둔다. 이후 사용자가 배송지를 바꾸거나 지워도 이미 접수된 배송은 바뀌지 않는다
    public Delivery createDelivery(Long orderId, UserAddress address) {
        return deliveryRepository.save(Delivery.of(orderId, address.getRecipientName(),
                address.getRecipientPhone(), address.getZipCode(), address.getAddress(), null));
    }
}