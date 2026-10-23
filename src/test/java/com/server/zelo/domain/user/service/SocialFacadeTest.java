package com.server.zelo.domain.user.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.user.dto.request.CreateSocialUserRequest;
import com.server.zelo.domain.user.dto.response.LoginResult;
import com.server.zelo.domain.user.entity.SocialProvider;
import com.server.zelo.domain.user.social.SocialProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class SocialFacadeTest {

    private static final CreateSocialUserRequest REQUEST = new CreateSocialUserRequest(
            "ticket",  "010-1234-5678", "집", "서울 강남구", "12345");
    private static final LoginResult RESULT = LoginResult.of("accessToken", "refreshToken");

    @InjectMocks
    private SocialFacade socialFacade;

    @Mock
    private SocialCommandService socialCommandService;

    @Mock
    private LockDistributeService lockDistributeService;

    @Test
    @SuppressWarnings("unchecked")
    void 번호표를_소비한_뒤_프로필_이메일_단위_락_안에서_가입한다() {
        // given
        SocialProfile profile = profile("jieun@gmail.com");
        given(socialCommandService.consumeSignupTicket("ticket")).willReturn(profile);
        given(socialCommandService.signup(REQUEST, profile)).willReturn(RESULT);
        // 락을 실제로 잡지 않고 넘겨받은 로직만 실행
        given(lockDistributeService.execute(eq(LockKey.createUser("jieun@gmail.com")), any(Supplier.class)))
                .willAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(1)).get());

        // when
        LoginResult result = socialFacade.createSocialUserWithAddress(REQUEST);

        // then
        assertThat(result).isEqualTo(RESULT);
        then(socialCommandService).should().signup(REQUEST, profile);
    }

    // 일반 가입은 이메일을 소문자로 바꿔서 락을 잡으므로, 소셜 가입도 같은 키여야 두 경로의 동시 가입이 한 줄로 처리됨
    @Test
    @SuppressWarnings("unchecked")
    void 이메일에_대문자가_섞여_있어도_일반_가입과_같은_소문자_락_키를_쓴다() {
        // given
        SocialProfile profile = profile("Jieun@Gmail.com");
        given(socialCommandService.consumeSignupTicket("ticket")).willReturn(profile);
        given(socialCommandService.signup(REQUEST, profile)).willReturn(RESULT);
        given(lockDistributeService.execute(eq(LockKey.createUser("jieun@gmail.com")), any(Supplier.class)))
                .willAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(1)).get());

        // when
        LoginResult result = socialFacade.createSocialUserWithAddress(REQUEST);

        // then
        assertThat(result).isEqualTo(RESULT);
    }

    @Test
    void 번호표가_유효하지_않으면_락을_잡지_않고_가입하지_않는다() {
        // given
        given(socialCommandService.consumeSignupTicket("ticket"))
                .willThrow(new BusinessException(ErrorCode.INVALID_SIGNUP_TOKEN));

        // when & then
        assertThatThrownBy(() -> socialFacade.createSocialUserWithAddress(REQUEST))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_SIGNUP_TOKEN);
        then(lockDistributeService).shouldHaveNoInteractions();
        then(socialCommandService).should(never()).signup(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 락을_잡지_못하면_가입하지_않고_예외가_전달된다() {
        // given
        SocialProfile profile = profile("jieun@gmail.com");
        given(socialCommandService.consumeSignupTicket("ticket")).willReturn(profile);
        given(lockDistributeService.execute(eq(LockKey.createUser("jieun@gmail.com")), any(Supplier.class)))
                .willThrow(new BusinessException(ErrorCode.TIMEOUT_EXCEPTION));

        // when & then
        assertThatThrownBy(() -> socialFacade.createSocialUserWithAddress(REQUEST))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TIMEOUT_EXCEPTION);
        then(socialCommandService).should(never()).signup(any(), any());
    }

    private SocialProfile profile(String email) {
        return SocialProfile.of(SocialProvider.GOOGLE, "1234567890", email, true, "지은");
    }
}
