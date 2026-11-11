package com.server.zelo.domain.order.dto.response;

import com.server.zelo.domain.delivery.entity.Delivery;
import com.server.zelo.domain.delivery.entity.DeliveryStatus;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderItem;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.entity.ShippingAddress;
import com.server.zelo.domain.order.entity.StockState;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.refund.entity.Refund;
import com.server.zelo.domain.refund.entity.RefundStatus;
import com.server.zelo.domain.refund.entity.RefundType;

import java.time.LocalDateTime;
import java.util.List;

public record GetOrderDetailResponse(
        Long orderId,
        String quoteId,
        String orderName,
        OrderStatus status,
        Long totalAmount,
        LocalDateTime createdAt,
        ShippingAddressInfo shippingAddress,
        List<OrderItemInfo> items,
        PaymentInfo payment,
        DeliveryInfo delivery,
        RefundInfo refund,
        Long refundedAmount,
        Long paidAmount
) {
    public static GetOrderDetailResponse from(
            Order order,
            Payment payment,
            Delivery delivery,
            Refund refund,
            Long refundedAmount
    ) {
        return new GetOrderDetailResponse(
                order.getId(),
                order.getQuoteId(),
                order.getOrderName(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCreatedAt(),
                ShippingAddressInfo.from(order.getShippingAddress()),
                order.getOrderItems().stream().map(OrderItemInfo::from).toList(),
                payment == null ? null : PaymentInfo.from(payment),
                delivery == null ? null : DeliveryInfo.from(delivery),
                refund == null ? null : RefundInfo.from(refund),
                refundedAmount,
                GetOrderSummaryResponse.paidAmount(order, refundedAmount)
        );
    }

    public record ShippingAddressInfo(
            String recipientName,
            String recipientPhone,
            String zipCode,
            String address
    ) {
        public static ShippingAddressInfo from(ShippingAddress address) {
            return new ShippingAddressInfo(
                    address.getRecipientName(),
                    address.getRecipientPhone(),
                    address.getZipCode(),
                    address.getAddress()
            );
        }
    }

    public record OrderItemInfo(
            Long orderItemId,
            Long productId,
            String productName,
            String productUnit,
            Long unitPrice,
            Integer quantity,
            Integer canceledQuantity,
            Integer cancelableQuantity,
            Long lineAmount,
            StockState stockState
    ) {
        public static OrderItemInfo from(OrderItem item) {
            return new OrderItemInfo(
                    item.getId(),
                    item.getProduct().getId(),
                    item.getProductName(),
                    item.getProductUnit(),
                    item.getUnitPrice(),
                    item.getQuantity(),
                    item.getCanceledQuantity(),
                    item.getCancelableQuantity(),
                    item.getLineAmount(),
                    item.getStockState()
            );
        }
    }

    public record PaymentInfo(
            Long paymentId,
            String portoneId,
            PaymentStatus paymentStatus,
            Long amount,
            String paymentMethodName,
            LocalDateTime paidAt,
            String failureReason
    ) {
        public static PaymentInfo from(Payment payment) {
            return new PaymentInfo(
                    payment.getId(),
                    payment.getPortoneId(),
                    payment.getStatus(),
                    payment.getAmount(),
                    payment.getPaymentMethodName(),
                    payment.getPaidAt(),
                    payment.getFailureReason()
            );
        }
    }

    public record DeliveryInfo(
            Long deliveryId,
            DeliveryStatus deliveryStatus,
            LocalDateTime shippedAt,
            LocalDateTime deliveredAt
    ) {
        public static DeliveryInfo from(Delivery delivery) {
            return new DeliveryInfo(
                    delivery.getId(),
                    delivery.getStatus(),
                    delivery.getShippedAt(),
                    delivery.getDeliveredAt()
            );
        }
    }

    public record RefundInfo(
            Long refundId,
            RefundStatus refundStatus,
            RefundType refundType,
            Long itemsAmount,
            LocalDateTime refundedAt,
            String reason
    ) {
        public static RefundInfo from(Refund refund) {
            return new RefundInfo(
                    refund.getId(),
                    refund.getStatus(),
                    refund.getRefundType(),
                    refund.getItemsAmount(),
                    refund.getRefundedAt(),
                    refund.getReason()
            );
        }
    }
}
