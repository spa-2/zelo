package com.server.zelo.domain.user.facade;

import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.user.dto.request.CreateUserAddressRequest;
import com.server.zelo.domain.user.dto.request.CreateUserRequest;
import com.server.zelo.domain.user.dto.request.UpdateUserAddressRequest;
import com.server.zelo.domain.user.dto.response.CreateUserAddressResponse;
import com.server.zelo.domain.user.dto.response.CreateUserResponse;
import com.server.zelo.domain.user.dto.response.UpdateUserAddressResponse;
import com.server.zelo.domain.user.service.UserAddressService;
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
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class UserFacadeTest {

    @InjectMocks
    private UserFacade userFacade;

    @Mock
    private UserCommandService userCommandService;

    @Mock
    private LockDistributeService lockDistributeService;

    @Mock
    private UserAddressService userAddressService;

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
    @SuppressWarnings("unchecked")
    void 사용자_단위_락_안에서_배송지를_등록한다() {
        // given
        CreateUserAddressRequest request =
                new CreateUserAddressRequest("회사", "홍길동", "010-1234-5678", "12345", "서울시 강남구", true);
        CreateUserAddressResponse response =
                new CreateUserAddressResponse(2L, "회사", "홍길동", "010-1234-5678", "12345", "서울시 강남구", true);
        given(userCommandService.createUserAddress(1L, request)).willReturn(response);
        // 락을 실제로 잡지 않고 넘겨받은 로직만 실행
        given(lockDistributeService.execute(eq(LockKey.createUserAddress(1L)), any(Supplier.class)))
                .willAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(1)).get());

        // when
        CreateUserAddressResponse result = userFacade.createUserAddress(1L, request);

        // then
        assertThat(result).isEqualTo(response);
        then(userCommandService).should().createUserAddress(1L, request);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 등록과_같은_사용자_단위_락_안에서_배송지를_수정한다() {
        // given
        UpdateUserAddressRequest request =
                new UpdateUserAddressRequest("회사", "김철수", "010-9876-5432", "54321", "서울시 중구", true);
        UpdateUserAddressResponse response =
                new UpdateUserAddressResponse(2L, "회사", "김철수", "010-9876-5432", "54321", "서울시 중구", true);
        given(userAddressService.updateUserAddress(1L, 2L, request)).willReturn(response);
        given(lockDistributeService.execute(eq(LockKey.createUserAddress(1L)), any(Supplier.class)))
                .willAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(1)).get());

        // when
        UpdateUserAddressResponse result = userFacade.updateUserAddress(1L, 2L, request);

        // then
        assertThat(result).isEqualTo(response);
    }

    @Test
    void 등록과_같은_사용자_단위_락_안에서_배송지를_삭제한다() {
        // given
        // 락을 실제로 잡지 않고 넘겨받은 로직만 실행
        willAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).given(lockDistributeService).execute(eq(LockKey.createUserAddress(1L)), any(Runnable.class));

        // when
        userFacade.deleteUserAddress(1L, 2L);

        // then
        then(userAddressService).should().deleteUserAddress(1L, 2L);
    }
}
