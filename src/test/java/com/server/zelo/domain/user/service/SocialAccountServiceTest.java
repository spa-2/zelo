package com.server.zelo.domain.user.service;

import com.server.zelo.domain.user.entity.SocialAccount;
import com.server.zelo.domain.user.entity.SocialProvider;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.repository.SocialAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class SocialAccountServiceTest {

    @InjectMocks
    private SocialAccountService socialAccountService;

    @Mock
    private SocialAccountRepository socialAccountRepository;

    @Test
    void 사용자와_제공자_정보로_소셜_계정을_저장한다() {
        // given
        User user = User.ofSocial("jieun@gmail.com", "지은", "010-1234-5678");

        // when
        socialAccountService.createSocialAccount(user, SocialProvider.GOOGLE, "1234567890");

        // then
        ArgumentCaptor<SocialAccount> captor = ArgumentCaptor.forClass(SocialAccount.class);
        then(socialAccountRepository).should().save(captor.capture());
        assertThat(captor.getValue().getUser()).isSameAs(user);
        assertThat(captor.getValue().getProvider()).isEqualTo(SocialProvider.GOOGLE);
        assertThat(captor.getValue().getProviderId()).isEqualTo("1234567890");
    }
}
