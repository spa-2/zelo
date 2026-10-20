package com.server.zelo.ai.llm;

import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import java.io.IOException;
import java.net.SocketTimeoutException;

// 테스트에서 OpenAI SDK가 실제로 던지는 예외를 같은 모양으로 만든다 (가짜 OpenAI 서버로 확인한 종류)
final class OpenAiErrors {

    private OpenAiErrors() {
    }

    static InternalServerException serverError(int status) {
        return InternalServerException.builder().statusCode(status).headers(Headers.builder().build()).build();
    }

    static RateLimitException rateLimit() {
        return RateLimitException.builder().headers(Headers.builder().build()).build();
    }

    static BadRequestException badRequest() {
        return BadRequestException.builder().headers(Headers.builder().build()).build();
    }

    static UnauthorizedException unauthorized() {
        return UnauthorizedException.builder().headers(Headers.builder().build()).build();
    }

    static OpenAIIoException httpTimeout() {
        return new OpenAIIoException("Request failed", new SocketTimeoutException("timeout"));
    }

    static OpenAIIoException connectionFailed() {
        return new OpenAIIoException("Request failed", new IOException("connection refused"));
    }
}
