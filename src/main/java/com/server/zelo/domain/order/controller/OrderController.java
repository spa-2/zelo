package com.server.zelo.domain.order.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.common.api.PageResponse;
import com.server.zelo.common.auth.Auth;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.domain.order.dto.request.CancelOrderRequest;
import com.server.zelo.domain.order.dto.request.CreateOrderConfirmationRequest;
import com.server.zelo.domain.order.dto.request.CreateOrderRequest;
import com.server.zelo.domain.order.dto.request.SearchOrderCondition;
import com.server.zelo.domain.order.dto.response.CancelOrderResponse;
import com.server.zelo.domain.order.dto.response.CreateOrderConfirmationResponse;
import com.server.zelo.domain.order.dto.response.CreateOrderResponse;
import com.server.zelo.domain.order.dto.response.GetOrderDetailResponse;
import com.server.zelo.domain.order.dto.response.GetOrderSummaryResponse;
import com.server.zelo.domain.order.model.CreateOrderResult;
import com.server.zelo.domain.order.service.OrderCommandService;
import com.server.zelo.domain.order.service.OrderFacade;
import com.server.zelo.domain.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderFacade orderFacade;
    private final OrderService orderService;
    private final OrderCommandService orderCommandService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<GetOrderSummaryResponse>>> getMyOrders(
            @Auth AuthUser authUser,
            // URL의 쿼리 파라미터를 검색 조건 DTO로 묶어 받는다는 의도를 명확히 하려고 붙인 @ModelAttribute
            @Valid @ModelAttribute SearchOrderCondition condition
    ) {
        return ResponseEntity.ok(ApiResponse.ok(orderService.getMyOrders(authUser.id(), condition)));
    }

    @PostMapping("/confirmations")
    public ResponseEntity<ApiResponse<CreateOrderConfirmationResponse>> createConfirmation(
            @Auth AuthUser authUser,
            @Valid @RequestBody CreateOrderConfirmationRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(orderCommandService.createConfirmation(authUser.id(), request)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CreateOrderResponse>> createOrder(
            @Auth AuthUser authUser,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        CreateOrderResult result = orderFacade.createOrder(authUser.id(), request);

        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(result.response()));
    }

    @PostMapping("/{orderId}/cancellations")
    public ResponseEntity<ApiResponse<CancelOrderResponse>> cancelOrder(
            @Auth AuthUser authUser,
            @PathVariable("orderId") Long orderId,
            // 취소 사유는 저장하지 않지만, 요청에서 필수 입력과 1~50자 조건을 검증
            @Valid @RequestBody CancelOrderRequest request
    ) {
        CancelOrderResponse response = orderFacade.cancelBeforePayment(authUser.id(), orderId);

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<GetOrderDetailResponse>> getOrderDetail(
            @Auth AuthUser authUser,
            @PathVariable("orderId") Long orderId
    ) {
        GetOrderDetailResponse response = orderCommandService.getOrderDetail(authUser.id(), orderId);

        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
