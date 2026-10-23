package com.server.zelo.domain.user.service;

import com.server.zelo.common.auth.JwtUtil;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.dto.request.CreateSocialUserRequest;
import com.server.zelo.domain.user.dto.response.LoginResult;
import com.server.zelo.domain.user.entity.SocialProvider;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.entity.UserAddress;
import com.server.zelo.domain.user.social.SocialProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class SocialCommandServiceTest {

    // 사용자 이름은 요청이 아니라 구글 프로필의 이름을 씀
    private static final SocialProfile PROFILE =
            SocialProfile.of(SocialProvider.GOOGLE, "1234567890", "jieun@gmail.com", true, "지은");
    private static final CreateSocialUserRequest REQUEST = new CreateSocialUserRequest(
            "ticket", "010-1234-5678", "집", "서울 강남구", "12345");

    @InjectMocks
    private SocialCommandService socialCommandService;

    @Mock
    private UserService userService;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private SocialAccountService socialAccountService;

    @Mock
    private UserAddressService userAddressService;

    @Mock
    private SocialSignupTicketService socialSignupTicketService;

    private User socialUser;
    private UserAddress userAddress;

    @BeforeEach
    void setUp() {
        socialUser = User.ofSocial("jieun@gmail.com", "지은", "010-1234-5678");
        ReflectionTestUtils.setField(socialUser, "id", 1L);
        userAddress = UserAddress.of(socialUser, "집", "지은", "010-1234-5678", "12345", "서울 강남구", true);
    }

    @Test
    void 번호표_소비는_번호표_서비스에_위임한다() {
        // given
        given(socialSignupTicketService.consume("ticket")).willReturn(PROFILE);

        // when
        SocialProfile result = socialCommandService.consumeSignupTicket("ticket");

        // then
        assertThat(result).isEqualTo(PROFILE);
    }

    @Test
    void 가입하면_사용자_소셜_계정_배송지를_순서대로_저장한다() {
        // given
        givenSignupSucceeds();

        // when
        socialCommandService.signup(REQUEST, PROFILE);

        // then
        // 소셜 계정과 배송지는 저장된 사용자가 있어야 만들 수 있음
        InOrder inOrder = inOrder(userService, socialAccountService, userAddressService, refreshTokenService);
        inOrder.verify(userService).createSocialUser(anyString(), anyString(), anyString());
        inOrder.verify(socialAccountService).createSocialAccount(any(User.class), any(SocialProvider.class), anyString());
        inOrder.verify(userAddressService)
                .createUserAddress(any(User.class), anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
        // 토큰은 가입이 모두 끝난 뒤에 발급해야 함
        inOrder.verify(refreshTokenService).saveRefreshToken(anyLong(), anyString());
    }

    @Test
    void 사용자는_프로필의_이메일과_이름_요청의_전화번호로_만든다() {
        // given
        givenSignupSucceeds();

        // when
        socialCommandService.signup(REQUEST, PROFILE);

        // then
        then(userService).should().createSocialUser("jieun@gmail.com", "지은", "010-1234-5678");
    }

    @Test
    void 소셜_계정에는_프로필의_제공자와_제공자_ID를_저장한다() {
        // given
        givenSignupSucceeds();

        // when
        socialCommandService.signup(REQUEST, PROFILE);

        // then
        // 제공자는 요청이 아니라 서버가 구글에서 확인한 프로필에서 와야 함
        then(socialAccountService).should().createSocialAccount(socialUser, SocialProvider.GOOGLE, "1234567890");
    }

    @Test
    void 받은_주소는_기본_배송지로_저장하고_받는_사람은_가입자_본인이다() {
        // given
        givenSignupSucceeds();

        // when
        socialCommandService.signup(REQUEST, PROFILE);

        // then
        then(userAddressService).should().createUserAddress(
                socialUser, "집", "지은", "010-1234-5678", "12345", "서울 강남구", true);
    }

    @Test
    void 가입하면_가입한_사용자의_토큰을_발급받아_반환한다() {
        // given
        givenSignupSucceeds();

        // when
        LoginResult result = socialCommandService.signup(REQUEST, PROFILE);

        // then
        assertThat(result.accessToken()).isEqualTo("accessToken");
        assertThat(result.refreshToken()).isEqualTo("refreshToken");
    }

    @Test
    void 발급한_리프레시_토큰은_Redis에_저장한다() {
        // given
        givenSignupSucceeds();

        // when
        socialCommandService.signup(REQUEST, PROFILE);

        // then
        // 저장하지 않으면 가입 직후 받은 리프레시 토큰으로 재발급과 로그아웃, 다른 기기 로그인 감지가 동작하지 않음
        then(refreshTokenService).should().saveRefreshToken(1L, "refreshToken");
    }

    @Test
    void 이메일이_이미_있으면_예외가_전달되고_소셜_계정과_배송지를_저장하지_않는다() {
        // given
        given(userService.createSocialUser(anyString(), anyString(), anyString()))
                .willThrow(new BusinessException(ErrorCode.DUPLICATE_EMAIL));

        // when & then
        assertThatThrownBy(() -> socialCommandService.signup(REQUEST, PROFILE))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_EMAIL);
        then(socialAccountService).shouldHaveNoInteractions();
        then(userAddressService).shouldHaveNoInteractions();
        // 가입에 실패했는데 토큰이 발급되거나 저장되면 안 됨
        then(jwtUtil).shouldHaveNoInteractions();
        then(refreshTokenService).shouldHaveNoInteractions();
    }

    private void givenSignupSucceeds() {
        given(userService.createSocialUser(anyString(), anyString(), anyString())).willReturn(socialUser);
        given(userAddressService.createUserAddress(
                any(User.class), anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .willReturn(userAddress);
        given(jwtUtil.createAccessToken(1L)).willReturn("accessToken");
        given(jwtUtil.createRefreshToken(1L)).willReturn("refreshToken");
    }
}
