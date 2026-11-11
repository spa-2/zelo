package com.server.zelo.domain.chat.entity;

import com.server.zelo.common.entity.BaseCreatedEntity;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "chat_rooms",
        indexes = @Index(name = "idx_chat_rooms_user_id_last_message_at", columnList = "user_id, last_message_at")
)
public class ChatRoom extends BaseCreatedEntity {

    private static final int TITLE_MAX_LENGTH = 30;
    private static final String TITLE_ELLIPSIS = "…";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 회원 도메인과 엔티티로 묶이지 않도록 ID만 보관하며, 사용자는 채팅방을 여러 개 가질 수 있어 unique 설정도 없습니다.
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 첫 사용자 메시지 앞부분으로 채워지며 그전에는 null이고 화면에서 "새 대화"로 보여줍니다.
    @Column(name = "title", nullable = true, length = 50)
    private String title;

    // 목록을 최근 대화순으로 정렬하기 위한 값으로, 생성 시각으로 시작해 메시지를 저장할 때마다 갱신
    @Column(name = "last_message_at", nullable = false)
    private LocalDateTime lastMessageAt;

    private ChatRoom(Long userId, LocalDateTime lastMessageAt) {
        this.userId = userId;
        this.lastMessageAt = lastMessageAt;
    }

    public static ChatRoom of(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return new ChatRoom(userId, LocalDateTime.now());
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    public void validateOwner(Long userId) {
        if (!isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED);
        }
    }

    // 첫 사용자 메시지로만 제목을 정하고, 한 번 정해진 제목은 이후 메시지로 바뀌지 않는다
    public void updateTitleIfAbsent(String firstMessage) {
        if (this.title != null || !StringUtils.hasText(firstMessage)) {
            return;
        }
        // 줄바꿈·연속 공백을 한 칸으로 줄여 목록에서 한 줄로 보이게 한다
        String normalized = firstMessage.strip().replaceAll("\\s+", " ");
        // 이모지가 반으로 잘리지 않도록 글자(code point) 단위로 자른다
        if (normalized.codePointCount(0, normalized.length()) <= TITLE_MAX_LENGTH) {
            this.title = normalized;
            return;
        }
        this.title = normalized.substring(0, normalized.offsetByCodePoints(0, TITLE_MAX_LENGTH)) + TITLE_ELLIPSIS;
    }

    public void updateLastMessageAt(LocalDateTime messageAt) {
        if (messageAt == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        this.lastMessageAt = messageAt;
    }
}
