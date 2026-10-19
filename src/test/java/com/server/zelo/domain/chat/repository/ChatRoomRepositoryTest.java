package com.server.zelo.domain.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.global.config.AuditingConfig;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

// 내장 DB 대신 실제 PostgreSQL로 정렬을 검증하고, created_at을 채우기 위해 Auditing 설정을 함께 띄운다
@DataJpaTest
@ActiveProfiles("test")
@Import(AuditingConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ChatRoomRepositoryTest {

    // 로컬 DB에 쌓인 데이터와 섞이지 않도록 실제로 쓰이지 않을 사용자 ID를 쓴다
    private static final Long USER_ID = 9_000_001L;
    private static final Long OTHER_USER_ID = 9_000_002L;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Test
    void 마지막_대화_시각이_최근인_방부터_조회한다() {
        // given
        LocalDateTime base = LocalDateTime.of(2026, 10, 19, 12, 0);
        ChatRoom oldTalk = saveRoom(USER_ID, base.minusHours(2));
        ChatRoom recentTalk = saveRoom(USER_ID, base);
        ChatRoom middleTalk = saveRoom(USER_ID, base.minusHours(1));

        // when
        List<ChatRoom> result = chatRoomRepository.findAllByUserIdOrderByLastMessageAtDescIdDesc(USER_ID);

        // then
        assertThat(result).extracting(ChatRoom::getId)
                .containsExactly(recentTalk.getId(), middleTalk.getId(), oldTalk.getId());
    }

    @Test
    void 마지막_대화_시각이_같으면_나중에_만든_방이_먼저_나온다() {
        // given
        LocalDateTime same = LocalDateTime.of(2026, 10, 19, 12, 0);
        ChatRoom first = saveRoom(USER_ID, same);
        ChatRoom second = saveRoom(USER_ID, same);

        // when
        List<ChatRoom> result = chatRoomRepository.findAllByUserIdOrderByLastMessageAtDescIdDesc(USER_ID);

        // then
        assertThat(result).extracting(ChatRoom::getId)
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    void 다른_사용자의_방은_조회되지_않는다() {
        // given
        ChatRoom mine = saveRoom(USER_ID, LocalDateTime.now());
        saveRoom(OTHER_USER_ID, LocalDateTime.now());

        // when
        List<ChatRoom> result = chatRoomRepository.findAllByUserIdOrderByLastMessageAtDescIdDesc(USER_ID);

        // then
        assertThat(result).extracting(ChatRoom::getId).containsExactly(mine.getId());
    }

    @Test
    void 방이_없으면_빈_목록을_반환한다() {
        // when
        List<ChatRoom> result = chatRoomRepository.findAllByUserIdOrderByLastMessageAtDescIdDesc(USER_ID);

        // then
        assertThat(result).isEmpty();
    }

    // last_message_at을 바꾸는 메서드는 메시지 전송 기능에서 생기므로, 정렬 검증을 위해 테스트에서만 값을 넣는다
    private ChatRoom saveRoom(Long userId, LocalDateTime lastMessageAt) {
        ChatRoom chatRoom = ChatRoom.of(userId);
        ReflectionTestUtils.setField(chatRoom, "lastMessageAt", lastMessageAt);
        return chatRoomRepository.saveAndFlush(chatRoom);
    }
}
