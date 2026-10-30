package com.server.zelo.domain.chat.service;

import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.RateLimitExceededException;
import com.server.zelo.common.monitoring.BusinessMetric;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.domain.chat.repository.ChatRateLimitRepository;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// LLM을 부르는 메시지 요청(새 대화, 메시지 전송, 다시 시도)을 사용자마다 센다.
// 연타로 비용이 늘거나, OpenAI 429가 쌓여 서킷이 열리면서 모든 사용자가 막히는 것을 막는다
@Service
@RequiredArgsConstructor
public class ChatRateLimitService {

    // 메시지 하나에 LLM을 2~3번 부르고 5~25초가 걸려서, 사람이 읽고 보내면 1분에 10번에 닿기 어렵다
    private static final int MAX_MESSAGES = 10;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final BusinessMetrics businessMetrics;
    private final ChatRateLimitRepository chatRateLimitRepository;

    public void validateLimit(Long userId) {
        long count = chatRateLimitRepository.increaseCount(userId, WINDOW);
        if (count > MAX_MESSAGES) {
            businessMetrics.count(BusinessMetric.CHAT_RATE_LIMITED);
            throw new RateLimitExceededException(ErrorCode.CHAT_RATE_LIMITED,
                    chatRateLimitRepository.getRemainingSeconds(userId));
        }
    }
}
