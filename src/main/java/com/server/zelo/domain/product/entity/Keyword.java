package com.server.zelo.domain.product.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 유림님(AI)이랑 맞춘 목록. AI가 사용자 말에서 뽑는 값이랑 같아야 매칭됨
// TODO(상품 담당 확인): AI 프롬프트에 키워드 뜻을 알려 주려면 한글 설명도 필요해서 AI 담당이 description을 추가함
@Getter
@RequiredArgsConstructor
public enum Keyword {
    NOT_SPICY("맵지 않은"),
    SPICY("매콤한"),
    SOFT("부드러운"),
    LOW_SALT("저염"),
    LOW_SUGAR("저당"),
    HIGH_PROTEIN("고단백"),
    VEGETARIAN("채식"),
    DOMESTIC("국내산"),
    ORGANIC("유기농"),
    EASY_COOK("간편 조리");

    private final String description;
}
