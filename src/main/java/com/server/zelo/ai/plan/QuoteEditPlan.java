package com.server.zelo.ai.plan;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// LLM이 견적 수정 요청을 정리한 수정 계획. 무엇을 고칠지만 담고, 실제 수정은 서버가 견적 담당 메서드로 한다
public record QuoteEditPlan(
        List<QuoteEdit> edits, // 말한 순서대로의 수정. 정할 수 없으면 빈 목록
        String question // 무엇을 고칠지 정할 수 없을 때 사용자에게 되물을 문장. 정했으면 빈 문자열
) {

    private static final int MAX_EDITS = 10;

    public static QuoteEditPlan of(List<QuoteEdit> edits, String question) {
        return new QuoteEditPlan(edits, question);
    }

    // LLM이 준 값은 믿지 않는다
    public void validate(Map<String, BaseUnit> ingredientUnits) {
        if (edits == null || question == null || edits.size() > MAX_EDITS) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        edits.forEach(edit -> edit.validate(ingredientUnits));
    }

    // 빼기·수량 변경이 모두 현재 견적의 상품을 가리키고, 한 상품을 두 번 고치지 않는지 본다.
    // 하나라도 어긋나면 아무것도 고치지 않아야 견적이 일부만 바뀌지 않는다
    public boolean isApplicableTo(Quote quote) {
        Set<Long> quoteProductIds = quote.items().stream().map(QuoteItem::productId).collect(Collectors.toSet());
        Set<Long> targets = new HashSet<>();
        return edits.stream()
                .filter(QuoteEdit::isProductEdit)
                .allMatch(edit -> quoteProductIds.contains(edit.productId()) && targets.add(edit.productId()));
    }
}
