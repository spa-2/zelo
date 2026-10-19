package com.server.zelo.domain.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.entity.ChatSender;
import com.server.zelo.global.config.AuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

// jsonb 매핑은 PostgreSQL에서만 확인할 수 있어 내장 DB 대신 실제 DB를 쓰고, created_at을 위해 Auditing 설정을 띄운다
@DataJpaTest
@ActiveProfiles("test")
@Import(AuditingConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ChatMessageRepositoryTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Test
    void 메시지를_저장하면_생성_시각이_채워지고_다시_읽을_수_있다() {
        // given
        ChatMessage saved = chatMessageRepository.saveAndFlush(ChatMessage.of(9_000_101L, ChatSender.USER, "안녕"));
        entityManager.clear();

        // when
        ChatMessage found = chatMessageRepository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(found.getRoomId()).isEqualTo(9_000_101L);
        assertThat(found.getSender()).isEqualTo(ChatSender.USER);
        assertThat(found.getContent()).isEqualTo("안녕");
        assertThat(found.getPayload()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void payload를_jsonb로_저장하고_다시_읽을_수_있다() {
        // given: payload를 채우는 기능은 견적 카드 작업에서 생기므로 테스트에서만 값을 넣는다
        ChatMessage chatMessage = ChatMessage.of(9_000_101L, ChatSender.ASSISTANT, "견적이에요.");
        ReflectionTestUtils.setField(chatMessage, "payload", "{\"type\":\"QUOTE_CARD\",\"totalAmount\":28900}");
        ChatMessage saved = chatMessageRepository.saveAndFlush(chatMessage);
        entityManager.clear();

        // when
        ChatMessage found = chatMessageRepository.findById(saved.getId()).orElseThrow();

        // then: jsonb는 공백을 정규화하므로 문자열 전체가 아니라 내용으로 비교한다
        assertThat(found.getPayload()).contains("\"type\"").contains("QUOTE_CARD").contains("28900");
    }

    @Test
    void 긴_메시지도_잘리지_않고_저장된다() {
        // given
        String longText = "가".repeat(1000);
        ChatMessage saved = chatMessageRepository.saveAndFlush(ChatMessage.of(9_000_101L, ChatSender.USER, longText));
        entityManager.clear();

        // when
        ChatMessage found = chatMessageRepository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(found.getContent()).hasSize(1000);
    }
}
