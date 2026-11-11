package com.server.zelo.ai.gateway;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.common.auth.Auth;
import com.server.zelo.common.auth.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// 장애 테스트 도중 OpenAI 장애가 끝난 상황(15초 지연 → 정상)을 만들어 서킷 브레이커가 스스로 닫히는지 보려고 둔다.
// fake 모드에서만 빈이 생겨서 운영(real)에는 이 API가 없다
@Slf4j
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "zelo.llm.mode", havingValue = "fake")
public class FakeLlmController {

    private final FakeLlmGateway fakeLlmGateway;

    // 지금 SecurityConfig가 모든 경로를 열어 두어서, 아무나 바꾸지 못하게 로그인 사용자만 받는다
    @PostMapping("/api/fake/llm/mode")
    public ResponseEntity<ApiResponse<Void>> changeMode(@Auth AuthUser authUser,
                                                        @Valid @RequestBody ChangeFakeLlmModeRequest request) {
        fakeLlmGateway.changeMode(request.mode());
        fakeLlmGateway.changeDelay(Duration.ofMillis(request.delayMillis()));
        fakeLlmGateway.changeDelayMax(Duration.ofMillis(request.delayMaxMillis()));
        log.info("fake LLM 모드 변경 userId={} mode={} delayMillis={} delayMaxMillis={}",
                authUser.id(), request.mode(), request.delayMillis(), request.delayMaxMillis());
        return ResponseEntity.ok(ApiResponse.ok());
    }

    public record ChangeFakeLlmModeRequest(
            @NotNull
            FakeLlmGateway.Mode mode,

            @NotNull
            @Min(0)
            Long delayMillis,

            @NotNull
            @Min(0)
            Long delayMaxMillis
    ) {
    }
}
