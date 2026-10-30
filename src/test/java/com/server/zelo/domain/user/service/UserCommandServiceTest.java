package com.server.zelo.domain.user.service;

import com.server.zelo.domain.user.entity.UserRole;
import com.server.zelo.common.auth.JwtUtil;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.RateLimitExceededException;
import com.server.zelo.domain.user.dto.request.CreateUserAddressRequest;
import com.server.zelo.domain.user.dto.request.CreateUserRequest;
import com.server.zelo.domain.user.dto.request.LoginRequest;
import com.server.zelo.domain.user.dto.response.CreateUserAddressResponse;
import com.server.zelo.domain.user.dto.response.CreateUserResponse;
import com.server.zelo.domain.user.dto.response.LoginResult;
import com.server.zelo.domain.user.emailverification.entity.EmailVerificationPurpose;
import com.server.zelo.domain.user.emailverification.service.EmailVerificationService;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.entity.UserAddress;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class UserCommandServiceTest {

    @InjectMocks
    private UserCommandService userCommandService;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private UserService userService;

    @Mock
    private UserAddressService userAddressService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private LoginAttemptService loginAttemptService;

    @Mock
    private EmailVerificationService emailVerificationService;

    private static final String CLIENT_IP = "203.0.113.7";
    private static final String TICKET = "A".repeat(43);

    @Test
    void 이메일_인증_티켓이_유효하면_회원과_기본_배송지를_저장한다() {
        // given
        CreateUserRequest request = createUserRequest("Password1!", "Password1!");
        User user = User.of("jieun@example.com", "encodedPassword", "홍길동", "010-1234-5678");
        given(userService.createUser("jieun@example.com", "Password1!", "홍길동", "010-1234-5678")).willReturn(user);
        given(userAddressService.createUserAddress(user, "집", "홍길동", "010-1234-5678", "12345", "서울 특별시 그곳", true))
                .willReturn(UserAddress.of(user, "집", "홍길동", "010-1234-5678", "12345", "서울 특별시 그곳", true));

        // when
        CreateUserResponse response = userCommandService.createUserWithAddress(request);

        // then 티켓은 가입 목적으로, 요청한 이메일과 함께 소비해야 함
        assertThat(response.email()).isEqualTo("jieun@example.com");
        then(emailVerificationService).should()
                .consumeTicket("jieun@example.com", TICKET, EmailVerificationPurpose.SIGNUP);
    }

    @Test
    void 이메일_인증_티켓이_유효하지_않으면_회원을_만들지_않는다() {
        // given
        CreateUserRequest request = createUserRequest("Password1!", "Password1!");
        willThrow(new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED))
                .given(emailVerificationService)
                .consumeTicket("jieun@example.com", TICKET, EmailVerificationPurpose.SIGNUP);

        // when & then
        assertThatThrownBy(() -> userCommandService.createUserWithAddress(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.EMAIL_VERIFICATION_REQUIRED));
        then(userService).should(never()).createUser(anyString(), anyString(), anyString(), anyString());
        then(userAddressService).shouldHaveNoInteractions();
    }

    @Test
    void 비밀번호_확인이_다르면_이메일_인증_티켓을_소비하지_않는다() {
        // given
        CreateUserRequest request = createUserRequest("Password1!", "Password2!");

        // when & then 고쳐서 다시 시도할 수 있는 실패로 인증이 날아가면 안 됨
        assertThatThrownBy(() -> userCommandService.createUserWithAddress(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PASSWORD_CONFIRMATION_MISMATCH));
        then(emailVerificationService).shouldHaveNoInteractions();
        then(userService).should(never()).createUser(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 이미_가입된_이메일이면_중복_예외가_발생하고_배송지를_저장하지_않는다() {
        // given
        CreateUserRequest request = createUserRequest("Password1!", "Password1!");
        given(userService.createUser("jieun@example.com", "Password1!", "홍길동", "010-1234-5678"))
                .willThrow(new BusinessException(ErrorCode.DUPLICATE_EMAIL));

        // when & then
        assertThatThrownBy(() -> userCommandService.createUserWithAddress(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_EMAIL));
        then(userAddressService).should(never()).createUserAddress(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void 로그인하면_토큰을_발급하고_리프레시_토큰을_저장한다() {
        // given
        LoginRequest request = new LoginRequest("jieun@example.com", "DemoPass!2026");
        User user = User.of("jieun@example.com", "encodedPassword", "지은", "010-1234-5678");
        ReflectionTestUtils.setField(user, "id", 1L);
        given(userService.getUserByEmailAndPassword("jieun@example.com", "DemoPass!2026")).willReturn(user);
        given(jwtUtil.createAccessToken(1L, UserRole.BASIC)).willReturn("accessToken");
        given(jwtUtil.createRefreshToken(1L, UserRole.BASIC)).willReturn("refreshToken");

        // when
        LoginResult result = userCommandService.login(request, CLIENT_IP);

        // then
        assertThat(result.accessToken()).isEqualTo("accessToken");
        assertThat(result.refreshToken()).isEqualTo("refreshToken");
        then(refreshTokenService).should().saveRefreshToken(1L, "refreshToken");
    }

    @Test
    void 로그인에_실패하면_토큰을_저장하지_않는다() {
        // given
        LoginRequest request = new LoginRequest("jieun@example.com", "Wrong1!");
        given(userService.getUserByEmailAndPassword("jieun@example.com", "Wrong1!"))
                .willThrow(new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        // when & then
        assertThatThrownBy(() -> userCommandService.login(request, CLIENT_IP))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        then(refreshTokenService).should(never()).saveRefreshToken(anyLong(), anyString());
    }

    @Test
    void 로그인하면_자격_증명을_검증하기_전에_시도_횟수를_먼저_올린다() {
        // given
        LoginRequest request = new LoginRequest("jieun@example.com", "DemoPass!2026");
        User user = User.of("jieun@example.com", "encodedPassword", "지은", "010-1234-5678");
        ReflectionTestUtils.setField(user, "id", 1L);
        given(userService.getUserByEmailAndPassword("jieun@example.com", "DemoPass!2026")).willReturn(user);
        given(jwtUtil.createAccessToken(1L, UserRole.BASIC)).willReturn("accessToken");
        given(jwtUtil.createRefreshToken(1L, UserRole.BASIC)).willReturn("refreshToken");

        // when
        userCommandService.login(request, CLIENT_IP);

        // then
        // 검증 뒤에 올리면 동시에 보낸 요청이 모두 검증을 통과해서 횟수 제한을 넘길 수 있음
        InOrder inOrder = inOrder(loginAttemptService, userService);
        inOrder.verify(loginAttemptService).attempt("jieun@example.com", CLIENT_IP);
        inOrder.verify(userService).getUserByEmailAndPassword("jieun@example.com", "DemoPass!2026");
    }

    @Test
    void 로그인에_성공하면_시도_횟수를_지운다() {
        // given
        LoginRequest request = new LoginRequest("jieun@example.com", "DemoPass!2026");
        User user = User.of("jieun@example.com", "encodedPassword", "지은", "010-1234-5678");
        ReflectionTestUtils.setField(user, "id", 1L);
        given(userService.getUserByEmailAndPassword("jieun@example.com", "DemoPass!2026")).willReturn(user);
        given(jwtUtil.createAccessToken(1L, UserRole.BASIC)).willReturn("accessToken");
        given(jwtUtil.createRefreshToken(1L, UserRole.BASIC)).willReturn("refreshToken");

        // when
        userCommandService.login(request, CLIENT_IP);

        // then
        then(loginAttemptService).should().deleteAttempt("jieun@example.com", CLIENT_IP);
    }

    @Test
    void 로그인에_실패하면_시도_횟수를_지우지_않는다() {
        // given
        LoginRequest request = new LoginRequest("jieun@example.com", "Wrong1!");
        given(userService.getUserByEmailAndPassword("jieun@example.com", "Wrong1!"))
                .willThrow(new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        // when & then
        assertThatThrownBy(() -> userCommandService.login(request, CLIENT_IP))
                .isInstanceOf(BusinessException.class);
        then(loginAttemptService).should(never()).deleteAttempt(anyString(), anyString());
    }

    @Test
    void 시도_횟수를_초과하면_자격_증명을_검증하지_않고_토큰도_발급하지_않는다() {
        // given
        LoginRequest request = new LoginRequest("jieun@example.com", "DemoPass!2026");
        willThrow(new RateLimitExceededException(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS, 540))
                .given(loginAttemptService).attempt("jieun@example.com", CLIENT_IP);

        // when & then
        // 잠긴 동안에는 맞는 비밀번호여도 거절해야 하므로 검증 자체를 하지 않음
        assertThatThrownBy(() -> userCommandService.login(request, CLIENT_IP))
                .isInstanceOf(RateLimitExceededException.class)
                .extracting(e -> ((RateLimitExceededException) e).getRetryAfterSeconds())
                .isEqualTo(540L);
        then(userService).shouldHaveNoInteractions();
        then(jwtUtil).shouldHaveNoInteractions();
        then(refreshTokenService).shouldHaveNoInteractions();
        then(loginAttemptService).should(never()).deleteAttempt(anyString(), anyString());
    }

    @Test
    void 재발급하면_새_토큰_둘을_발급하고_받은_리프레시_토큰과_함께_교체를_요청한다() {
        // given
        given(jwtUtil.validateToken("oldRefreshToken")).willReturn(true);
        given(jwtUtil.isRefreshToken("oldRefreshToken")).willReturn(true);
        given(jwtUtil.getUserId("oldRefreshToken")).willReturn(1L);
        given(jwtUtil.getUserRole("oldRefreshToken")).willReturn(UserRole.BASIC);
        given(jwtUtil.createAccessToken(1L, UserRole.BASIC)).willReturn("newAccessToken");
        given(jwtUtil.createRefreshToken(1L, UserRole.BASIC)).willReturn("newRefreshToken");

        // when
        LoginResult result = userCommandService.reissue("oldRefreshToken");

        // then
        assertThat(result.accessToken()).isEqualTo("newAccessToken");
        assertThat(result.refreshToken()).isEqualTo("newRefreshToken");
        // 새 토큰이 아니라 사용자가 보낸 토큰으로 일치 여부를 확인해야 함
        then(refreshTokenService).should().rotateToken(1L, "oldRefreshToken", "newRefreshToken");
    }

    @Test
    void 재발급하면_리프레시_토큰의_role을_새_토큰에_그대로_담는다() {
        // given
        given(jwtUtil.validateToken("oldRefreshToken")).willReturn(true);
        given(jwtUtil.isRefreshToken("oldRefreshToken")).willReturn(true);
        given(jwtUtil.getUserId("oldRefreshToken")).willReturn(1L);
        given(jwtUtil.getUserRole("oldRefreshToken")).willReturn(UserRole.ADMIN);
        given(jwtUtil.createAccessToken(1L, UserRole.ADMIN)).willReturn("newAccessToken");
        given(jwtUtil.createRefreshToken(1L, UserRole.ADMIN)).willReturn("newRefreshToken");

        // when
        LoginResult result = userCommandService.reissue("oldRefreshToken");

        // then
        assertThat(result.accessToken()).isEqualTo("newAccessToken");
        assertThat(result.refreshToken()).isEqualTo("newRefreshToken");
    }

    @Test
    void 위조되었거나_만료되었거나_비어있는_토큰이면_INVALID_TOKEN_예외가_발생하고_재발급하지_않는다() {
        // given
        given(jwtUtil.validateToken("invalidToken")).willReturn(false);

        // when & then
        assertThatThrownBy(() -> userCommandService.reissue("invalidToken"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
        then(jwtUtil).should(never()).getUserId(anyString());
        then(refreshTokenService).shouldHaveNoInteractions();
    }

    @Test
    void 액세스_토큰으로는_재발급할_수_없다() {
        // given
        given(jwtUtil.validateToken("accessToken")).willReturn(true);
        given(jwtUtil.isRefreshToken("accessToken")).willReturn(false);

        // when & then
        assertThatThrownBy(() -> userCommandService.reissue("accessToken"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
        then(jwtUtil).should(never()).getUserId(anyString());
        then(refreshTokenService).shouldHaveNoInteractions();
    }

    @Test
    void 교체에_실패하면_새_토큰을_반환하지_않고_예외가_전달된다() {
        // given
        given(jwtUtil.validateToken("oldRefreshToken")).willReturn(true);
        given(jwtUtil.isRefreshToken("oldRefreshToken")).willReturn(true);
        given(jwtUtil.getUserId("oldRefreshToken")).willReturn(1L);
        given(jwtUtil.getUserRole("oldRefreshToken")).willReturn(UserRole.BASIC);
        given(jwtUtil.createAccessToken(1L, UserRole.BASIC)).willReturn("newAccessToken");
        given(jwtUtil.createRefreshToken(1L, UserRole.BASIC)).willReturn("newRefreshToken");
        willThrow(new BusinessException(ErrorCode.TOKEN_REUSED))
                .given(refreshTokenService).rotateToken(1L, "oldRefreshToken", "newRefreshToken");

        // when & then
        assertThatThrownBy(() -> userCommandService.reissue("oldRefreshToken"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_REUSED);
    }

    @Test
    void 기본_배송지로_등록하면_기존_기본_배송지를_해제한다() {
        // given
        User user = mock(User.class);
        CreateUserAddressRequest request = addressRequest(true);
        given(userService.getUser(1L)).willReturn(user);
        given(userAddressService.createUserAddress(user, "회사", "홍길동", "010-1234-5678", "12345", "서울시 강남구", true))
                .willReturn(UserAddress.of(user, "회사", "홍길동", "010-1234-5678", "12345", "서울시 강남구", true));

        // when
        CreateUserAddressResponse response = userCommandService.createUserAddress(1L, request);

        // then
        assertThat(response.isDefault()).isTrue();
        then(userAddressService).should().releaseDefaultAddress(1L);
    }

    @Test
    void 기본_배송지가_아니면_기존_기본_배송지를_유지한다() {
        // given
        User user = mock(User.class);
        CreateUserAddressRequest request = addressRequest(false);
        given(userService.getUser(1L)).willReturn(user);
        given(userAddressService.createUserAddress(user, "회사", "홍길동", "010-1234-5678", "12345", "서울시 강남구", false))
                .willReturn(UserAddress.of(user, "회사", "홍길동", "010-1234-5678", "12345", "서울시 강남구", false));

        // when
        CreateUserAddressResponse response = userCommandService.createUserAddress(1L, request);

        // then
        assertThat(response.isDefault()).isFalse();
        then(userAddressService).should(never()).releaseDefaultAddress(anyLong());
    }

    @Test
    void 같은_이름의_배송지가_있으면_저장하지_않는다() {
        // given
        given(userService.getUser(1L)).willReturn(mock(User.class));
        willThrow(new BusinessException(ErrorCode.DUPLICATE_ADDRESS_PLACE))
                .given(userAddressService).validatePlaceNotDuplicated(1L, "회사");

        // when
        // then
        assertThatThrownBy(() -> userCommandService.createUserAddress(1L, addressRequest(true)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_ADDRESS_PLACE));
        then(userAddressService).should(never()).releaseDefaultAddress(anyLong());
    }

    private CreateUserRequest createUserRequest(String password, String passwordConfirm) {
        return new CreateUserRequest("Jieun@Example.com", TICKET, password, passwordConfirm, "홍길동",
                "010-1234-5678", "집", "서울 특별시 그곳", "12345");
    }

    private CreateUserAddressRequest addressRequest(boolean isDefault) {
        return new CreateUserAddressRequest("회사", "홍길동", "010-1234-5678", "12345", "서울시 강남구", isDefault);
    }
}
