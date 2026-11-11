package com.server.zelo.domain.order.entity;

public enum ActorType {
    USER, // 사용자 본인 (웹 또는 채팅의 버튼 클릭)
    AGENT, // AI 에이전트 연결을 통한 요청
    SYSTEM // 스케줄러, 웹훅 등 서버 내부 처리. 감사 로그에서만 사용
}
