package com.server.zelo.domain.product.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 유림님(AI)이랑 맞춘 목록. AI가 사용자 말에서 뽑는 값이랑 같아야 매칭됨
// TODO(상품 담당 확인): AI 프롬프트에 키워드 뜻을 알려 주려면 한글 설명도 필요해서 AI 담당이 description을 추가함
// 괄호 안 예시는 AI가 사용자 말을 키워드로 바꿀 때 보는 말이라, 키워드를 추가하면 예시도 같이 적어야 함
@Getter
@RequiredArgsConstructor
public enum Keyword {
    NOT_SPICY("맵지 않은 (예: 안 맵게, 덜 맵게, 순한 맛, 매운 거 못 먹음, 초등학생, 아이)"),
    SPICY("매콤한 (예: 맵게, 얼큰하게, 칼칼하게, 화끈하게, 매운 거 좋아함)"),
    SOFT("부드러운 (예: 부드럽게, 연하게, 씹기 편하게, 이가 약해서, 잘 못 씹어서)"),
    LOW_SALT("저염 (예: 싱겁게, 짜지 않게, 간 약하게, 나트륨 적게, 혈압 때문에)"),
    LOW_SUGAR("저당 (예: 달지 않게, 당 적게, 설탕 빼고, 당뇨 때문에, 혈당)"),
    HIGH_PROTEIN("고단백 (예: 단백질 많이, 운동, 헬스, 근육, 벌크업)"),
    VEGETARIAN("채식 (예: 채식, 고기 빼고, 고기 안 먹음, 비건)"),
    DOMESTIC("국내산 (예: 국산으로, 국내산만, 수입산 말고)"),
    ORGANIC("유기농 (예: 유기농으로, 친환경, 무농약)"),
    EASY_COOK("간편 조리 (예: 빨리, 간단하게, 바빠서, 바쁨, 손질 없이, 요리 초보)");

    private final String description;
}
