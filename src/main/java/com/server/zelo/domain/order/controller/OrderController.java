package com.server.zelo.domain.order.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.common.api.PageResponse;
import com.server.zelo.common.auth.Auth;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.domain.order.dto.request.SearchOrderCondition;
import com.server.zelo.domain.order.dto.response.GetOrderSummaryResponse;
import com.server.zelo.domain.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<GetOrderSummaryResponse>>> getAllOrders(
            Pageable pageable,
            @Auth AuthUser authUser,
            // URL의 쿼리 파라미터를 검색 조건 DTO로 묶어 받는다는 의도를 명확히 하려고 붙인 @ModelAttribute
            @Valid @ModelAttribute SearchOrderCondition searchOrder
    ) {
        return ResponseEntity
                .ok(ApiResponse
                        .ok(orderService.getMyOrders(pageable, authUser.id(), searchOrder))
                );
    }
}
