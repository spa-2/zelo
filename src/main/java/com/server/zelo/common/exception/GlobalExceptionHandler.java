package com.server.zelo.common.exception;


import com.server.zelo.common.api.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j(topic = "service")
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 비즈니스 예외를 해당 오류 코드의 HTTP 상태와 예외 메시지를 담은 응답으로 변환한다.
     * 예외에 data가 있으면 함께 담고, 없으면 data는 null이다.
     *
     * @param e 처리할 비즈니스 예외
     * @return 오류 코드의 HTTP 상태, 응답 코드와 예외 메시지(와 data)를 담은 오류 응답
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> handleBusinessException(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.getStatus())
                .body(ApiResponse.error(code, e.getMessage(), e.getData()));
    }

    /**
     * 필드 검증 오류를 "필드명: 메시지" 형식으로 쉼표로 연결해 잘못된 입력 응답을 만든다.
     * 필드 오류가 없으면 기본 입력 오류 메시지를 사용한다.
     *
     * @param e 요청 인자 검증 중 발생한 예외
     * @return HTTP 400 상태와 {@link ErrorCode#INVALID_INPUT} 코드를 담은 오류 응답
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + ", " + b)
                .orElse("입력값이 올바르지 않습니다");
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, message));
    }

    /**
     * 누락되었거나 읽을 수 없는 요청 본문을 기본 입력 오류 응답으로 변환한다.
     *
     * @param e 요청 본문을 읽는 중 발생한 예외
     * @return HTTP 400 상태와 {@link ErrorCode#INVALID_INPUT}의 기본 메시지를 담은 오류 응답
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.info("body값 누락");
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT));
    }

    /**
     * 처리되지 않은 예외를 로그에 기록하고 내부 예외 내용 없이 기본 서버 오류 응답을 반환한다.
     *
     * @param e 처리되지 않은 예외
     * @return HTTP 500 상태와 {@link ErrorCode#INTERNAL_ERROR}의 기본 메시지를 담은 오류 응답
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("서버 오류 발생", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR));
    }

}
