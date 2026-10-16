package com.server.zelo.common.api;


import com.server.zelo.common.exception.ErrorCode;

public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data
) {

    // data가 있는 정상 응답
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, null, null, data
        );
    }

    // data가 없는 정상 응답
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null, null
        );
    }

    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return new ApiResponse<>(false, errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, errorCode.getCode(), message, null);
    }
}