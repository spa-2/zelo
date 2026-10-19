package com.server.zelo.domain.chat.repository;

import com.server.zelo.domain.chat.entity.ChatRoom;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    // 마지막 대화 시각이 같으면 나중에 만든 방(id가 큰 방)을 위로 올려 순서가 매번 같도록 구성
    List<ChatRoom> findAllByUserIdOrderByLastMessageAtDescIdDesc(Long userId);
}
