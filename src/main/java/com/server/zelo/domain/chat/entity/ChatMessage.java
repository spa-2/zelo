package com.server.zelo.domain.chat.entity;

import com.server.zelo.common.entity.BaseCreatedEntity;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.util.StringUtils;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "chat_messages",
        indexes = @Index(name = "idx_chat_messages_room_id_id", columnList = "room_id, id")
)
public class ChatMessage extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 메시지를 읽을 때 방 엔티티가 필요 없고 지연 로딩 문제를 피하려고 ID만 보관한다
    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender", nullable = false, length = 20)
    private ChatSender sender;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    // 견적 카드·버튼처럼 화면에 그릴 구조화 데이터(JSON). 그때 보여 준 화면을 그대로 복원하기 위한 스냅샷이다
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = true, columnDefinition = "jsonb")
    private String payload;

    private ChatMessage(Long roomId, ChatSender sender, String content) {
        this.roomId = roomId;
        this.sender = sender;
        this.content = content;
    }

    public static ChatMessage of(Long roomId, ChatSender sender, String content) {
        if (roomId == null || sender == null || !StringUtils.hasText(content)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return new ChatMessage(roomId, sender, content);
    }
}
