package com.server.zelo.common.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    // 실패 응답에도 화면에 보여 줄 데이터. 대부분의 예외는 null이다 (예외는 직렬화 대상이라 transient)
    private final transient Object data;

    /**
     * 오류 코드에 정의된 기본 메시지로 비즈니스 예외를 생성한다.
     *
     * @param errorCode HTTP 상태, 응답 코드와 기본 메시지를 제공하는 오류 코드
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.data = null;
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
        this.data = null;
    }

    /**
     * 실패했지만 화면에 보여 줄 데이터가 있을 때, 그 데이터를 오류 응답의 data에 담는 비즈니스 예외를 생성한다.
     *
     * @param errorCode HTTP 상태와 응답 코드를 제공하는 오류 코드
     * @param message 예외와 API 오류 응답에 사용할 메시지
     * @param data API 오류 응답의 data에 담을 값
     */
    public BusinessException(ErrorCode errorCode, String message, Object data) {
        super(message);
        this.errorCode = errorCode;
        this.data = data;
    }
}
