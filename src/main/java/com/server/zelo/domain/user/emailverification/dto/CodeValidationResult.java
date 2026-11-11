package com.server.zelo.domain.user.emailverification.dto;

// 인증 코드 확인 결과. 저장소(Lua)의 숫자 결과를 의미 있는 이름으로 바꿔서 서비스가 숫자를 알지 않게 한다
public enum CodeValidationResult {
    SUCCESS, // 코드 일치
    EXPIRED, // 코드가 없거나 만료
    MISMATCH, // 코드 불일치
    TOO_MANY_FAILURES // 실패 횟수 초과로 코드가 폐기됨
}
