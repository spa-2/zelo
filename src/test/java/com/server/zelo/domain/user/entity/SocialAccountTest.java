package com.server.zelo.domain.user.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SocialAccountTest {

    @Test
    void 사용자와_제공자_제공자_ID로_소셜_계정을_만든다() {
        // given
        User user = User.ofSocial("jieun@gmail.com", "지은", "010-1234-5678");

        // when
        SocialAccount socialAccount = SocialAccount.of(user, SocialProvider.GOOGLE, "1234567890");

        // then
        assertThat(socialAccount.getUser()).isSameAs(user);
        assertThat(socialAccount.getProvider()).isEqualTo(SocialProvider.GOOGLE);
        assertThat(socialAccount.getProviderId()).isEqualTo("1234567890");
    }
}
