package com.server.zelo.ai.prompt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

// 채팅 답변용 system 프롬프트. 규칙(주문·결제 불가, 숫자는 서버 값만)을 코드가 아닌 파일에서 고칠 수 있게 파일로 둔다
@Component
public class ChatPrompt {

    private final String systemPrompt;

    public ChatPrompt(@Value("classpath:prompts/chat.md") Resource template) {
        this.systemPrompt = readTemplate(template);
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    private static String readTemplate(Resource template) {
        try {
            return template.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
