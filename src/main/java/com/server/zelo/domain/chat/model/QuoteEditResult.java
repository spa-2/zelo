package com.server.zelo.domain.chat.model;

import com.server.zelo.ai.plan.QuoteEdit;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.quote.model.Quote;
import java.util.List;
import java.util.Objects;

// LLM 수정 계획을 견적에 적용한 결과. 세 경우 중 하나만 될 수 있고, 각 경우에 필요한 값이 빠진 결과는 만들 수 없다.
// 받는 쪽은 switch로 세 경우를 모두 처리해야 컴파일된다
public sealed interface QuoteEditResult {

    // 계획의 수정을 모두 적용했다
    record Edited(Quote quote, List<QuoteEdit> appliedEdits) implements QuoteEditResult {

        public Edited {
            Objects.requireNonNull(quote);
            appliedEdits = requireApplied(appliedEdits);
        }
    }

    // 앞의 수정은 저장됐는데 failedEdit에서 멈췄다. 저장된 견적(새 견적 ID)으로 카드를 만들어야 채팅의 카드와 어긋나지 않는다
    record PartiallyEdited(Quote quote, List<QuoteEdit> appliedEdits, QuoteEdit failedEdit, ErrorCode failure)
            implements QuoteEditResult {

        public PartiallyEdited {
            Objects.requireNonNull(quote);
            appliedEdits = requireApplied(appliedEdits);
            Objects.requireNonNull(failedEdit);
            Objects.requireNonNull(failure);
        }
    }

    // 하나도 고치지 못했다. 사용자에게 정해진 문장으로 안내할 수 있는 이유만 담는다(그 밖의 오류는 던진다)
    record NotEdited(ErrorCode reason) implements QuoteEditResult {

        public NotEdited {
            if (QuoteEditReply.from(reason).isEmpty()) {
                throw new IllegalArgumentException("안내할 수 없는 수정 실패 이유: " + reason);
            }
        }
    }

    private static List<QuoteEdit> requireApplied(List<QuoteEdit> appliedEdits) {
        if (appliedEdits == null || appliedEdits.isEmpty()) {
            throw new IllegalArgumentException("적용한 수정이 없습니다");
        }
        return List.copyOf(appliedEdits);
    }
}
