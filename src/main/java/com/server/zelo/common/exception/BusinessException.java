package com.server.zelo.common.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * 오류 코드에 정의된 기본 메시지로 비즈니스 예외를 생성한다.
     *
     * @param errorCode HTTP 상태, 응답 코드와 기본 메시지를 제공하는 오류 코드
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 오류 코드의 기본 메시지 대신 지정한 메시지로 비즈니스 예외를 생성한다.
     *
     * @param errorCode HTTP 상태와 응답 코드를 제공하는 오류 코드
     * @param message 예외와 API 오류 응답에 사용할 메시지
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
