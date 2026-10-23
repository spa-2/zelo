package com.server.zelo.ai.llm;

import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.product.entity.BaseUnit;
import java.util.List;
import java.util.Map;

public interface LlmGateway {

    // history: 이번 메시지 앞의 대화(오래된 순). LLM은 기억이 없어서 앞 대화를 매번 같이 보낸다
    LlmReply explain(String systemPrompt, List<ChatMessage> history, String userText);

    // 사용자 말을 장보기 메모(MenuPlan)로 정리한다. JSON 응답을 객체로 바꾸는 것까지 한다.
    // ingredientUnits: 프롬프트에 넣은 재료 목록. 답의 재료 단위가 이 목록과 맞는지 검증하는 데 쓴다
    LlmResult<MenuPlan> planMenu(String systemPrompt, List<ChatMessage> history, String userText,
                                 Map<String, BaseUnit> ingredientUnits);

    // 한 상품의 리뷰 목록을 한 줄로 요약한다. 채팅 요청이 아니라 미리 만들어 저장해 두는 용도다
    LlmReply summarizeReviews(String systemPrompt, String reviewText);
}
