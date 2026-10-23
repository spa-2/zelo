package com.server.zelo.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Common
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_001", "입력값이 올바르지 않습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_002", "서버 내부 오류가 발생했습니다."),
    TIMEOUT_EXCEPTION(HttpStatus.CONFLICT, "COMMON_003", "요청이 처리 중입니다. 잠시 후 다시 시도해 주세요."),
    // 기능별 전용 코드(AI_001, AUTH_004)가 없는 곳에서 Redis 같은 인프라가 일시적으로 안 될 때 쓰는 공통 코드
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "COMMON_004", "일시적으로 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."),

    // Member
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER_001", "회원을 찾을 수 없습니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "MEMBER_002", "이미 존재하는 이메일입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "MEMBER_003", "이메일 또는 비밀번호가 올바르지 않습니다."),
    PASSWORD_CONFIRMATION_MISMATCH(HttpStatus.BAD_REQUEST, "MEMBER_004", "비밀번호와 확인 비밀번호가 일치하지 않습니다."),
    INVALID_ADMIN_ROLE_EXCEPTION(HttpStatus.BAD_REQUEST, "MEMBER_005", "어드민 회원가입에서는 NORMAL 역할을 사용할 수 없습니다." ),
    INACTIVE_ACCOUNT(HttpStatus.FORBIDDEN, "MEMBER_006", "비활성화 계정입니다"),
    USER_ADDRESS_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER_007", "배송지를 찾을 수 없습니다."),
    DUPLICATE_ADDRESS_PLACE(HttpStatus.CONFLICT, "MEMBER_009", "같은 이름으로 등록된 배송지가 있습니다."),
    DEFAULT_ADDRESS_REQUIRED(HttpStatus.BAD_REQUEST, "MEMBER_008", "기본 배송지는 다른 배송지를 기본으로 지정한 뒤 변경하거나 삭제할 수 있습니다."),

    // Product
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "PRODUCT_001", "상품을 찾을 수 없습니다."),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "PRODUCT_002", "재고가 부족합니다."),
    INVALID_PRICE(HttpStatus.BAD_REQUEST, "PRODUCT_003", "가격은 0 이상이어야 합니다."),
    INVALID_STOCK(HttpStatus.BAD_REQUEST, "PRODUCT_004", "재고는 0 이상이어야 합니다."),
    INVALID_PRICE_RANGE(HttpStatus.BAD_REQUEST, "PRODUCT_005", "최소가격은 최대가격보다 작아야 합니다."),

    // Cart
    CART_EMPTY(HttpStatus.BAD_REQUEST, "CART_001", "장바구니가 비어있습니다."),
    CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "CART_002", "장바구니 항목을 찾을 수 없습니다."),
    INVALID_QUANTITY(HttpStatus.BAD_REQUEST, "CART_003", "수량은 1 이상이어야 합니다."),
    CART_NOT_FOUND(HttpStatus.BAD_REQUEST, "CART_004", "장바구니가 존재하지 않습니다."),

    // Order
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_001", "주문을 찾을 수 없습니다."),
    INVALID_ORDER_STATUS(HttpStatus.BAD_REQUEST, "ORDER_002", "유효하지 않은 주문 상태 변경입니다."),
    ORDER_ACCESS_DENIED(HttpStatus.FORBIDDEN, "ORDER_003", "해당 주문에 접근할 권한이 없습니다."),

    // Payment
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT_001", "결제 정보를 찾을 수 없습니다."),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "PAYMENT_002", "결제 금액이 일치하지 않습니다."),
    INVALID_PAYMENT_STATUS(HttpStatus.BAD_REQUEST, "PAYMENT_003", "유효하지 않은 결제 상태 변경입니다."),
    PAYMENT_NOT_PAID(HttpStatus.BAD_REQUEST, "PAYMENT_004", "PG사 결제가 완료되지 않았습니다."),
    ALREADY_PROCESSED_PAYMENT(HttpStatus.CONFLICT, "PAYMENT_005", "이미 처리된 결제입니다."),
    PG_REQUEST_FAILED(HttpStatus.BAD_GATEWAY, "PAYMENT_006", "결제사 요청에 실패했습니다."),
    PG_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "PAYMENT_007", "결제사 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요."),

    // Webhook
    INVALID_WEBHOOK_SIGNATURE(HttpStatus.UNAUTHORIZED, "WEBHOOK_001", "웹훅 서명이 유효하지 않습니다."),
    WEBHOOK_EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "WEBHOOK_002", "웹훅 이벤트를 찾을 수 없습니다."),

    // Chat
    CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "CHAT_001", "채팅방을 찾을 수 없습니다."),
    CHAT_ROOM_ACCESS_DENIED(HttpStatus.FORBIDDEN, "CHAT_002", "해당 채팅방에 접근할 권한이 없습니다."),

    // Review
    REVIEW_SUMMARY_NOT_FOUND(HttpStatus.NOT_FOUND, "REVIEW_001", "상품의 리뷰 요약을 찾을 수 없습니다."),

    // AI
    AI_RESPONSE_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "AI_001", "AI 응답을 받지 못했습니다. 잠시 후 다시 시도해 주세요."),
    AI_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "AI_002", "AI 응답 시간이 초과되었습니다."),
    AI_RATE_LIMITED(HttpStatus.SERVICE_UNAVAILABLE, "AI_003", "AI 요청이 많아 잠시 처리할 수 없습니다."),
    AI_SERVER_ERROR(HttpStatus.BAD_GATEWAY, "AI_004", "AI 서비스에 일시적인 오류가 발생했습니다."),
    AI_NETWORK_ERROR(HttpStatus.BAD_GATEWAY, "AI_005", "AI 서비스에 연결하지 못했습니다."),
    AI_REQUEST_REJECTED(HttpStatus.BAD_GATEWAY, "AI_006", "AI 서비스가 요청을 처리하지 못했습니다."),
    AI_EMPTY_RESPONSE(HttpStatus.BAD_GATEWAY, "AI_007", "AI가 빈 응답을 보냈습니다."),
    AI_CIRCUIT_OPEN(HttpStatus.SERVICE_UNAVAILABLE, "AI_008", "AI 서비스를 잠시 사용할 수 없습니다."),
    AI_INVALID_RESPONSE(HttpStatus.BAD_GATEWAY, "AI_009", "요청을 이해하지 못했어요. 다시 말씀해 주세요."),

    // Auth
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH_001", "인증이 필요합니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_002", "유효하지 않은 토큰입니다."),
    NO_AUTHORITY(HttpStatus.FORBIDDEN, "AUTH_003", "권한이 없는 요청입니다."),
    TOKEN_REVOKE_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_004", "로그아웃 처리에 실패했습니다. 잠시 후 다시 시도해 주세요."),
    LOGGED_IN_ELSEWHERE(HttpStatus.UNAUTHORIZED, "AUTH_005", "다른 기기에서 로그인이 감지되었습니다. 다시 로그인하세요."),
    // 탈취인지 본인 실수인지 알 수 없어서 문구에는 탈취라고 쓰지 않음
    TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "AUTH_006", "세션이 만료되었습니다. 다시 로그인하세요."),
    // 같은 토큰의 재발급 요청이 동시에 오거나 응답이 유실되어 재시도된 경우로, 이 토큰으로 다시 시도하라는 뜻이 아님
    TOKEN_ALREADY_ROTATED(HttpStatus.CONFLICT, "AUTH_007", "이미 재발급된 토큰입니다. 잠시 후 다시 시도하거나 다시 로그인하세요."),

    //Refund
    INVALID_REFUND_STATUS(HttpStatus.BAD_REQUEST, "REFUND_001", "유효하지 않은 환불 상태 변경입니다."),
    INVALID_REFUND_QUANTITY(HttpStatus.BAD_REQUEST, "REFUND_002", "환불 수량은 1 이상, 주문 수량 이하여야 합니다."),
    // Delivery
    INVALID_DELIVERY_STATUS(HttpStatus.BAD_REQUEST, "DELIVERY_001", "유효하지 않은 배송 상태 변경입니다.");


    private final HttpStatus status;
    private final String code;
    private final String message;
}