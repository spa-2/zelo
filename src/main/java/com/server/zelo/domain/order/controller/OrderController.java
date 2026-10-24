package com.server.zelo.domain.order.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.common.api.PageResponse;
import com.server.zelo.common.auth.Auth;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.domain.order.dto.request.CreateOrderConfirmationRequest;
import com.server.zelo.domain.order.dto.request.SearchOrderCondition;
import com.server.zelo.domain.order.dto.response.CreateOrderConfirmationResponse;
import com.server.zelo.domain.order.dto.response.GetOrderSummaryResponse;
import com.server.zelo.domain.order.service.OrderCommandService;
import com.server.zelo.domain.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderCommandService orderCommandService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<GetOrderSummaryResponse>>> getMyOrders(
            @Auth AuthUser authUser,
            // URL의 쿼리 파라미터를 검색 조건 DTO로 묶어 받는다는 의도를 명확히 하려고 붙인 @ModelAttribute
            @Valid @ModelAttribute SearchOrderCondition condition
    ) {
        return ResponseEntity
                .ok(ApiResponse.ok(orderService.getMyOrders(authUser.id(), condition)));
    }

    @PostMapping("/confirmations")
    public ResponseEntity<ApiResponse<CreateOrderConfirmationResponse>> createOrder(
            @Auth AuthUser authUser,
            @Valid @RequestBody CreateOrderConfirmationRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(orderCommandService.createConfirmation(authUser.id(), request)));
    }
}
