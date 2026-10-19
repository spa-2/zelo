package com.server.zelo.domain.chat.repository;

import com.server.zelo.domain.chat.entity.ChatMessage;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    // 첫 화면: 가장 최근 메시지부터
    List<ChatMessage> findByRoomIdOrderByIdDesc(Long roomId, Limit limit);

    // 위로 스크롤: 커서(before)보다 오래된 메시지부터. id 순서가 곧 저장 순서라 시간 대신 id를 기준으로 쓴다
    List<ChatMessage> findByRoomIdAndIdLessThanOrderByIdDesc(Long roomId, Long before, Limit limit);
}
