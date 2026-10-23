package com.server.zelo.domain.quote.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.repository.QuoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QuoteService {

    private final QuoteRepository quoteRepository;

    // 주문 만들 때 사용자가 본 견적이 아직 그 견적인지 확인. quoteId가 다르면 그 사이에 견적이 수정된 거라 만료로 봄
    public Quote validateForOrder(Long roomId, Long userId, String quoteId) {
        Quote quote = quoteRepository.findByRoomId(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUOTE_EXPIRED));
        if (!quote.userId().equals(userId)) {
            throw new BusinessException(ErrorCode.NO_AUTHORITY);
        }
        if (!quote.quoteId().equals(quoteId)) {
            throw new BusinessException(ErrorCode.QUOTE_EXPIRED);
        }
        return quote;
    }
}