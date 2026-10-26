package com.server.zelo.ai.gateway;

import com.server.zelo.ai.plan.MenuPlan;
import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import org.springframework.ai.converter.BeanOutputConverter;

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

    // 구조화 출력에서 LLM 응답을 객체로 바꾸지 못할 때 Spring AI(BeanOutputConverter)가 던지는 예외를 그대로 만든다
    static RuntimeException invalidJson() {
        try {
            new BeanOutputConverter<>(MenuPlan.class).convert("메뉴 계획이 아닌 문장");
        } catch (RuntimeException e) {
            return e;
        }
        throw new IllegalStateException("JSON이 아닌데 변환에 성공했다");
    }
}
