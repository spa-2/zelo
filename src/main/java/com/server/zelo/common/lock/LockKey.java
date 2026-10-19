package com.server.zelo.common.lock;

public class LockKey {

    private LockKey(){}

    // Todo 도메인이 늘어남에 따라 키 관련 필드가 많이 늘어날거라 어떻게 할지 논의 필요
    private static final String USER_CREATE = "lock:user:create:";
    private static final String USER_LOGIN = "lock:user:login:";

    // 같은 이메일로 동시에 가입해도 중복 검사를 같이 통과하지 못하게 이메일 단위로 락을 잡음
    public static String createUser(String email) {
        return USER_CREATE + email;
    }

    // 같은 계정으로 동시에 로그인해도 리프레시 토큰이 순서대로 저장되도록 이메일 단위로 락을 잡음
    public static String login(String email) {
        return USER_LOGIN + email;
    }
}
