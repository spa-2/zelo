package com.server.zelo.domain.user.facade;

import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.user.dto.request.CreateUserRequest;
import com.server.zelo.domain.user.dto.request.LoginRequest;
import com.server.zelo.domain.user.dto.response.CreateUserResponse;
import com.server.zelo.domain.user.dto.response.LoginResult;
import com.server.zelo.domain.user.service.UserCommandService;
import com.server.zelo.domain.user.service.UserFacade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class UserFacadeTest {

    @InjectMocks
    private UserFacade userFacade;

    @Mock
    private UserCommandService userCommandService;

    @Mock
    private LockDistributeService lockDistributeService;

    @Test
    @SuppressWarnings("unchecked")
    void 이메일_단위_락_안에서_회원가입을_실행한다() {
        // given
        CreateUserRequest request = new CreateUserRequest(
                "test@example.com", "Password1!", "Password1!", "홍길동",
                "010-1234-5678", "집", "서울 특별시 그곳", "12345");
        CreateUserResponse response = CreateUserResponse.of(
                1L, "test@example.com", "홍길동", "집", "서울 특별시 그곳", "12345", LocalDateTime.now());
        given(userCommandService.createUserWithAddress(request)).willReturn(response);
        // 락을 실제로 잡지 않고 넘겨받은 로직만 실행
        given(lockDistributeService.execute(eq(LockKey.createUser("test@example.com")), any(Supplier.class)))
                .willAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(1)).get());

        // when
        CreateUserResponse result = userFacade.createUserWithAddress(request);

        // then
        assertThat(result).isEqualTo(response);
        then(userCommandService).should().createUserWithAddress(request);
    }

    @Test
    void 로그인은_락_없이_실행한다() {
        // given
        LoginRequest request = new LoginRequest("Jieun@example.com", "DemoPass!2026");
        LoginResult response = LoginResult.of("accessToken", "refreshToken");
        given(userCommandService.login(request)).willReturn(response);

        // when
        LoginResult result = userFacade.login(request);

        // then
        assertThat(result).isEqualTo(response);
        then(userCommandService).should().login(request);
        then(lockDistributeService).shouldHaveNoInteractions();
    }
}
