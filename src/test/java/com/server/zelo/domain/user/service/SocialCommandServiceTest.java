package com.server.zelo.domain.user.service;

import com.server.zelo.domain.user.entity.UserRole;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import com.server.zelo.domain.user.dto.request.SocialLoginRequest;
import com.server.zelo.domain.user.dto.response.ExistingMemberResult;
import com.server.zelo.domain.user.dto.response.NewMemberResult;
import com.server.zelo.domain.user.dto.response.SocialLoginResult;
import com.server.zelo.domain.user.entity.SocialAccount;
import com.server.zelo.domain.user.social.SocialLoginClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(MockitoExtension.class)
class SocialCommandServiceTest {

    // 사용자 이름은 요청이 아니라 구글 프로필의 이름을 씀
    private static final SocialProfile PROFILE =
            SocialProfile.of(SocialProvider.GOOGLE, "1234567890", "jieun@gmail.com", true, "지은");
    private static final CreateSocialUserRequest REQUEST = new CreateSocialUserRequest(
            "ticket", "010-1234-5678", "집", "서울 강남구", "12345");

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

    @Mock
    private SocialLoginClient socialLoginClient;

    private User socialUser;
    private UserAddress userAddress;

    @BeforeEach
    void setUp() {
        // 제공자별 클라이언트 목록(List)은 @InjectMocks로 넣을 수 없어서 직접 만듦
        socialCommandService = new SocialCommandService(jwtUtil, userService, userAddressService, refreshTokenService,
                socialAccountService, List.of(socialLoginClient), socialSignupTicketService);
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
        given(jwtUtil.createAccessToken(1L, UserRole.BASIC)).willReturn("accessToken");
        given(jwtUtil.createRefreshToken(1L, UserRole.BASIC)).willReturn("refreshToken");
    }

    // ===== 소셜 로그인 =====

    private static final String CODE = "authorization-code";
    private static final String REDIRECT_URI = "http://localhost:3000/callback";
    private static final SocialLoginRequest LOGIN_REQUEST = new SocialLoginRequest(CODE, REDIRECT_URI, null);

    @Test
    void 이미_가입한_소셜_계정이면_사용자의_토큰을_발급해_기존_회원_결과로_돌려준다() {
        // given
        givenProfileFromGoogle(PROFILE);
        givenSocialAccountExists(99L);
        given(jwtUtil.createAccessToken(1L, UserRole.BASIC)).willReturn("accessToken");
        given(jwtUtil.createRefreshToken(1L, UserRole.BASIC)).willReturn("refreshToken");

        // when
        SocialLoginResult result = socialCommandService.login("google", LOGIN_REQUEST);

        // then
        assertThat(result).isEqualTo(new ExistingMemberResult("accessToken", "refreshToken"));
        then(refreshTokenService).should().saveRefreshToken(1L, "refreshToken");
        then(socialSignupTicketService).shouldHaveNoInteractions();
    }

    // 소셜 계정의 ID(99)가 아니라 그 계정이 연결된 사용자의 ID(1)로 토큰을 만들어야 함
    // 소셜 계정 ID를 쓰면 번호가 우연히 같은 다른 사용자의 토큰이 발급됨
    @Test
    void 토큰은_소셜_계정_ID가_아니라_연결된_사용자_ID로_발급한다() {
        // given
        givenProfileFromGoogle(PROFILE);
        givenSocialAccountExists(99L);
        given(jwtUtil.createAccessToken(1L, UserRole.BASIC)).willReturn("accessToken");
        given(jwtUtil.createRefreshToken(1L, UserRole.BASIC)).willReturn("refreshToken");

        // when
        socialCommandService.login("google", LOGIN_REQUEST);

        // then
        then(jwtUtil).should(never()).createAccessToken(99L, UserRole.BASIC);
        then(refreshTokenService).should(never()).saveRefreshToken(eq(99L), anyString());
    }

    // 소셜 계정은 하드코딩된 값이 아니라 구글이 알려 준 제공자 ID로 찾아야 함
    @Test
    void 소셜_계정은_프로필의_제공자_ID로_조회한다() {
        // given
        SocialProfile profile = SocialProfile.of(SocialProvider.GOOGLE, "another-provider-id", "jieun@gmail.com", true, "지은");
        givenProfileFromGoogle(profile);
        given(socialAccountService.findByProviderAndProviderId(SocialProvider.GOOGLE, "another-provider-id"))
                .willReturn(Optional.empty());
        given(socialSignupTicketService.issue(profile)).willReturn("ticket");

        // when
        socialCommandService.login("google", LOGIN_REQUEST);

        // then
        then(socialAccountService).should().findByProviderAndProviderId(SocialProvider.GOOGLE, "another-provider-id");
    }

    @Test
    void 이미_가입한_사용자는_이메일이_없거나_인증되지_않아도_로그인된다() {
        // given
        // 신원은 제공자 ID이고 이메일 정책은 신규 가입자에게만 적용함
        SocialProfile noEmail = SocialProfile.of(SocialProvider.GOOGLE, "1234567890", null, false, "지은");
        givenProfileFromGoogle(noEmail);
        givenSocialAccountExists(99L);
        given(jwtUtil.createAccessToken(1L, UserRole.BASIC)).willReturn("accessToken");
        given(jwtUtil.createRefreshToken(1L, UserRole.BASIC)).willReturn("refreshToken");

        // when
        SocialLoginResult result = socialCommandService.login("google", LOGIN_REQUEST);

        // then
        assertThat(result).isInstanceOf(ExistingMemberResult.class);
    }

    @Test
    void 처음_온_사람이면_번호표를_발급하고_토큰은_발급하지_않는다() {
        // given
        givenProfileFromGoogle(PROFILE);
        given(socialAccountService.findByProviderAndProviderId(SocialProvider.GOOGLE, "1234567890"))
                .willReturn(Optional.empty());
        given(userService.existsByEmail("jieun@gmail.com")).willReturn(false);
        given(socialSignupTicketService.issue(PROFILE)).willReturn("ticket");

        // when
        SocialLoginResult result = socialCommandService.login("google", LOGIN_REQUEST);

        // then
        assertThat(result).isEqualTo(new NewMemberResult("ticket", "jieun@gmail.com", "지은"));
        // 가입 전에는 로그인되면 안 됨
        then(jwtUtil).shouldHaveNoInteractions();
        then(refreshTokenService).shouldHaveNoInteractions();
    }

    @Test
    void 처음_온_사람의_이메일이_없으면_예외가_발생하고_번호표를_발급하지_않는다() {
        // given
        givenProfileFromGoogle(SocialProfile.of(SocialProvider.GOOGLE, "1234567890", null, false, "지은"));
        given(socialAccountService.findByProviderAndProviderId(SocialProvider.GOOGLE, "1234567890"))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> socialCommandService.login("google", LOGIN_REQUEST))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SOCIAL_EMAIL_REQUIRED);
        then(socialSignupTicketService).shouldHaveNoInteractions();
    }

    @Test
    void 처음_온_사람의_이메일이_비어있으면_예외가_발생한다() {
        // given
        givenProfileFromGoogle(SocialProfile.of(SocialProvider.GOOGLE, "1234567890", " ", true, "지은"));
        given(socialAccountService.findByProviderAndProviderId(SocialProvider.GOOGLE, "1234567890"))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> socialCommandService.login("google", LOGIN_REQUEST))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SOCIAL_EMAIL_REQUIRED);
    }

    // 인증되지 않은 이메일로 가입을 허용하면 남의 이메일로 가입하거나 계정을 가로챌 수 있음
    @Test
    void 처음_온_사람의_이메일이_인증되지_않았으면_예외가_발생하고_번호표를_발급하지_않는다() {
        // given
        givenProfileFromGoogle(SocialProfile.of(SocialProvider.GOOGLE, "1234567890", "jieun@gmail.com", false, "지은"));
        given(socialAccountService.findByProviderAndProviderId(SocialProvider.GOOGLE, "1234567890"))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> socialCommandService.login("google", LOGIN_REQUEST))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SOCIAL_EMAIL_REQUIRED);
        then(socialSignupTicketService).shouldHaveNoInteractions();
    }

    @Test
    void 같은_이메일로_이미_가입된_사용자가_있으면_예외가_발생하고_번호표를_발급하지_않는다() {
        // given
        givenProfileFromGoogle(PROFILE);
        given(socialAccountService.findByProviderAndProviderId(SocialProvider.GOOGLE, "1234567890"))
                .willReturn(Optional.empty());
        given(userService.existsByEmail("jieun@gmail.com")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> socialCommandService.login("google", LOGIN_REQUEST))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SOCIAL_EMAIL_CONFLICT);
        then(socialSignupTicketService).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"google", "GOOGLE", "Google"})
    void 제공자_이름은_대소문자를_구분하지_않는다(String provider) {
        // given
        givenProfileFromGoogle(PROFILE);
        given(socialAccountService.findByProviderAndProviderId(SocialProvider.GOOGLE, "1234567890"))
                .willReturn(Optional.empty());
        given(socialSignupTicketService.issue(PROFILE)).willReturn("ticket");

        // when
        SocialLoginResult result = socialCommandService.login(provider, LOGIN_REQUEST);

        // then
        assertThat(result).isInstanceOf(NewMemberResult.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"facebook", "unknown", "", " "})
    void 지원하지_않는_제공자면_예외가_발생하고_제공자를_호출하지_않는다(String provider) {
        // when & then
        assertThatThrownBy(() -> socialCommandService.login(provider, LOGIN_REQUEST))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_SOCIAL_PROVIDER);
        then(socialLoginClient).shouldHaveNoInteractions();
    }

    @Test
    void 제공자가_null이면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> socialCommandService.login(null, LOGIN_REQUEST))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_SOCIAL_PROVIDER);
    }

    @Test
    void 제공자는_있지만_구현된_클라이언트가_없으면_예외가_발생한다() {
        // given
        SocialCommandService withoutClients = new SocialCommandService(jwtUtil, userService, userAddressService,
                refreshTokenService, socialAccountService, List.of(), socialSignupTicketService);

        // when & then
        assertThatThrownBy(() -> withoutClients.login("google", LOGIN_REQUEST))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_SOCIAL_PROVIDER);
    }

    @Test
    void 클라이언트에는_요청의_code_redirectUri_codeVerifier를_그대로_전달한다() {
        // given
        String verifier = "a".repeat(43);
        given(socialLoginClient.getProvider()).willReturn(SocialProvider.GOOGLE);
        given(socialLoginClient.getProfile(CODE, REDIRECT_URI, verifier)).willReturn(PROFILE);
        given(socialAccountService.findByProviderAndProviderId(SocialProvider.GOOGLE, "1234567890"))
                .willReturn(Optional.empty());
        given(socialSignupTicketService.issue(PROFILE)).willReturn("ticket");

        // when
        socialCommandService.login("google", new SocialLoginRequest(CODE, REDIRECT_URI, verifier));

        // then
        then(socialLoginClient).should().getProfile(CODE, REDIRECT_URI, verifier);
    }

    @Test
    void 제공자가_code를_거절하면_예외가_전달되고_이후_조회를_하지_않는다() {
        // given
        given(socialLoginClient.getProvider()).willReturn(SocialProvider.GOOGLE);
        given(socialLoginClient.getProfile(CODE, REDIRECT_URI, null))
                .willThrow(new BusinessException(ErrorCode.SOCIAL_AUTH_FAILED));

        // when & then
        assertThatThrownBy(() -> socialCommandService.login("google", LOGIN_REQUEST))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SOCIAL_AUTH_FAILED);
        then(socialAccountService).shouldHaveNoInteractions();
        then(socialSignupTicketService).shouldHaveNoInteractions();
    }

    private void givenProfileFromGoogle(SocialProfile profile) {
        given(socialLoginClient.getProvider()).willReturn(SocialProvider.GOOGLE);
        given(socialLoginClient.getProfile(anyString(), anyString(), any())).willReturn(profile);
    }

    // 소셜 계정 ID는 99, 연결된 사용자 ID는 1로 서로 다르게 둠
    private void givenSocialAccountExists(Long socialAccountId) {
        SocialAccount account = SocialAccount.of(socialUser, SocialProvider.GOOGLE, "1234567890");
        ReflectionTestUtils.setField(account, "id", socialAccountId);
        given(socialAccountService.findByProviderAndProviderId(SocialProvider.GOOGLE, "1234567890"))
                .willReturn(Optional.of(account));
    }

    // ===== 이름 보정 =====

    @Test
    void 가입할_때_구글_이름이_없으면_이메일_앞부분을_이름으로_저장한다() {
        // given
        SocialProfile noName = SocialProfile.of(SocialProvider.GOOGLE, "1234567890", "jieun@gmail.com", true, null);
        given(userService.createSocialUser("jieun@gmail.com", "jieun", "010-1234-5678")).willReturn(socialUser);
        given(userAddressService.createUserAddress(
                any(User.class), anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .willReturn(userAddress);
        given(jwtUtil.createAccessToken(1L, UserRole.BASIC)).willReturn("accessToken");
        given(jwtUtil.createRefreshToken(1L, UserRole.BASIC)).willReturn("refreshToken");

        // when
        socialCommandService.signup(REQUEST, noName);

        // then
        // 이름이 null인 채로 저장하면 users.name NOT NULL 위반으로 가입이 실패함
        then(userService).should().createSocialUser("jieun@gmail.com", "jieun", "010-1234-5678");
    }

    @Test
    void 가입할_때_구글_이름이_50자를_넘으면_50자로_잘라_저장한다() {
        // given
        SocialProfile longName = SocialProfile.of(SocialProvider.GOOGLE, "1234567890", "jieun@gmail.com", true, "가".repeat(60));
        given(userService.createSocialUser("jieun@gmail.com", "가".repeat(50), "010-1234-5678")).willReturn(socialUser);
        given(userAddressService.createUserAddress(
                any(User.class), anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .willReturn(userAddress);
        given(jwtUtil.createAccessToken(1L, UserRole.BASIC)).willReturn("accessToken");
        given(jwtUtil.createRefreshToken(1L, UserRole.BASIC)).willReturn("refreshToken");

        // when
        socialCommandService.signup(REQUEST, longName);

        // then
        then(userService).should().createSocialUser("jieun@gmail.com", "가".repeat(50), "010-1234-5678");
    }

    @Test
    void 처음_온_사람에게_보여_줄_이름도_가입에_저장될_이름과_같다() {
        // given
        SocialProfile noName = SocialProfile.of(SocialProvider.GOOGLE, "1234567890", "jieun@gmail.com", true, null);
        givenProfileFromGoogle(noName);
        given(socialAccountService.findByProviderAndProviderId(SocialProvider.GOOGLE, "1234567890"))
                .willReturn(Optional.empty());
        given(userService.existsByEmail("jieun@gmail.com")).willReturn(false);
        given(socialSignupTicketService.issue(noName)).willReturn("ticket");

        // when
        SocialLoginResult result = socialCommandService.login("google", LOGIN_REQUEST);

        // then
        // 가입 화면에 미리 채워 준 이름과 저장되는 이름이 다르면 사용자가 혼란스러움
        assertThat(result).isEqualTo(new NewMemberResult("ticket", "jieun@gmail.com", "jieun"));
    }
}
