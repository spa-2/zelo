package com.server.zelo.domain.chat.model;

import com.server.zelo.common.exception.ErrorCode;
import java.util.Arrays;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 견적을 고치지 못한 이유가 정해져 있어 LLM 없이 답하는 문장. LLM이 고친 척 말하지 않게 한다.
// failure가 있는 문장은 견적 수정 메서드의 그 오류를 사용자에게 안내할 때 쓴다
@Getter
@RequiredArgsConstructor
public enum QuoteEditReply {

    NO_QUOTE_TO_EDIT(ErrorCode.QUOTE_EXPIRED, "고칠 견적이 없어요. 견적이 만료됐을 수 있으니 만들 메뉴를 다시 말씀해 주세요."),
    QUOTE_CHANGED(ErrorCode.QUOTE_NOT_CURRENT, "견적이 방금 바뀌어서 고치지 않았어요. 최신 견적 카드를 확인하고 다시 말씀해 주세요."),
    NOT_IN_QUOTE(ErrorCode.PRODUCT_NOT_FOUND, "견적 카드에 없는 상품이라 고치지 못했어요. 카드에 있는 상품 이름으로 다시 말씀해 주세요."),
    NOT_ENOUGH_STOCK(ErrorCode.INSUFFICIENT_STOCK, "재고가 부족해서 그 수량으로 바꿀 수 없어요. 수량을 줄여서 다시 말씀해 주세요."),
    ASK_EDIT_AGAIN(null, "어떤 상품을 어떻게 바꿀지 다시 말씀해 주세요."),
    // 대체 후보와 예산 후보에 같이 쓴다
    CANDIDATE_NOT_FOUND(null, "이미 고른 후보예요. 최신 견적 카드를 확인해 주세요."),
    QUOTE_IN_PROGRESS(null, "이 채팅방의 견적을 처리하고 있는 다른 요청이 있어요. 잠시 후 다시 말씀해 주세요.");

    private final ErrorCode failure;
    private final String text;

    // 사용자가 다시 말하면 되는 오류만 문장이 있다. 그 밖의 오류(Redis 장애 등)는 빈 값이라 부르는 쪽이 그대로 던진다
    public static Optional<QuoteEditReply> from(ErrorCode failure) {
        return Arrays.stream(values())
                .filter(reply -> reply.failure != null && reply.failure == failure)
                .findFirst();
    }
}
