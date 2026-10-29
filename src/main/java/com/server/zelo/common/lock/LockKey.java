package com.server.zelo.common.lock;

public class LockKey {
    private LockKey() {
    }

    // Todo 도메인이 늘어남에 따라 키 관련 필드가 많이 늘어날거라 어떻게 할지 논의 필요
    private static final String USER_CREATE = "lock:user:create:";
    private static final String PAYMENT_CONFIRM = "lock:payment:confirm:";
    private static final String USER_ADDRESS = "lock:user:address:";
    private static final String ORDER_CREATE = "lock:order:create:";
    private static final String REFUND = "lock:refund:";
    private static final String QUOTE = "lock:quote:";
    private static final String PAYMENT_RETRY = "lock:payment:retry:";

    // 같은 이메일로 동시에 가입해도 중복 검사를 같이 통과하지 못하게 이메일 단위로 락을 잡음
    public static String createUser(String email) {
        return USER_CREATE + email;
    }

    public static String confirmPayment(String portoneId) {
        return PAYMENT_CONFIRM + portoneId;
    }

    public static String createUserAddress(Long userId) { return USER_ADDRESS + userId;}

    // 등록과 같은 키를 써서 같은 사용자의 등록·수정이 동시에 기본 배송지를 바꾸지 못하게 함
    public static String updateUserAddress(Long userId) {return USER_ADDRESS + userId;}

    // 등록·수정과 같은 키를 써서 삭제 도중 기본 배송지가 바뀌지 않게 함
    public static String deleteUserAddress(Long userId) {return USER_ADDRESS + userId;
    }

    // 다른 견적의 주문도 동시에 결제 대기 상태로 생성되지 않도록 사용자별로 잠금
    public static String createOrder(Long userId) {
        return ORDER_CREATE + userId;
    }

    // 같은 주문의 환불 요청이 겹쳐 포트원 취소가 두 번 나가지 않도록 주문 단위로 락을 잡음
    public static String refund(Long orderId) {return REFUND + orderId;}

    // 견적은 채팅방마다 하나라 방 단위로 잠가, 같은 방의 견적 발급·수정이 수정의 확인과 적용 사이에 끼어들지 못하게 함
    public static String quote(Long roomId) {
        return QUOTE + roomId;
    }

    // 재결제 연타로 같은 주문에 새 결제가 여러 개 생기지 않도록 주문 단위로 잠금
    public static String retryPayment(Long orderId) {return PAYMENT_RETRY + orderId;}


}
