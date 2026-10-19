package com.server.zelo.ai.llm;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "zelo.llm.mode", havingValue = "fake")
public class FakeLlmGateway implements LlmGateway {

    public static final String MODEL = "fake";

    public enum Mode { NORMAL, DELAY, FAIL }

    // 테스트 스레드에서 바꾼 모드를 요청 스레드가 바로 보도록 volatile
    private volatile Mode mode = Mode.NORMAL;
    private volatile Duration delay = Duration.ofSeconds(6);

    @Override
    public LlmReply explain(String systemPrompt, String userText) {
        switch (mode) {
            case DELAY -> sleep(delay);
            case FAIL -> throw new IllegalStateException("fake llm failure");
            case NORMAL -> { }
        }
        return LlmReply.of("[FAKE] " + userText + "에 대한 답변입니다.", MODEL, 0, 0);
    }

    public void changeMode(Mode mode) {
        this.mode = mode;
    }

    public void changeDelay(Duration delay) {
        this.delay = delay;
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("fake llm delay interrupted");
        }
    }
}
