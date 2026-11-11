package com.server.zelo.domain.product.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KeywordTest {

    @Test
    void 짧은_이름은_괄호_안_예시를_뺀_부분이다() {
        // when & then
        assertThat(Keyword.NOT_SPICY.getLabel()).isEqualTo("맵지 않은");
        assertThat(Keyword.EASY_COOK.getLabel()).isEqualTo("간편 조리");
    }
}