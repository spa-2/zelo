package com.server.zelo.domain.user.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.dto.request.UpdateUserAddressRequest;
import com.server.zelo.domain.user.dto.response.GetUserAddressResponse;
import com.server.zelo.domain.user.dto.response.UpdateUserAddressResponse;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.entity.UserAddress;
import com.server.zelo.domain.user.repository.UserAddressRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class UserAddressServiceTest {

    @Mock
    private UserAddressRepository userAddressRepository;

    @InjectMocks
    private UserAddressService userAddressService;

    @Test
    void 조회한_순서대로_배송지_목록을_돌려준다() {
        // given
        User user = mock(User.class);
        UserAddress home = UserAddress.of(user, "집", "홍길동", "010-1234-5678", "12345", "서울시 강남구", true);
        UserAddress office = UserAddress.of(user, "회사", "홍길동", "010-1234-5678", "54321", "서울시 중구", false);
        given(userAddressRepository.findAllActiveByUserId(1L)).willReturn(List.of(home, office));

        // when
        List<GetUserAddressResponse> responses = userAddressService.getUserAddresses(1L);

        // then
        assertThat(responses).extracting(GetUserAddressResponse::place).containsExactly("집", "회사");
        assertThat(responses).extracting(GetUserAddressResponse::isDefault).containsExactly(true, false);
    }

    @Test
    void 배송지가_없으면_빈_목록을_돌려준다() {
        // given
        given(userAddressRepository.findAllActiveByUserId(1L)).willReturn(List.of());

        // when
        List<GetUserAddressResponse> responses = userAddressService.getUserAddresses(1L);

        // then
        assertThat(responses).isEmpty();
    }

    @Test
    void 배송지_정보를_수정한다() {
        // given
        UserAddress address = UserAddress.of(mock(User.class), "집", "홍길동", "010-1234-5678", "12345", "서울시", false);
        given(userAddressRepository.findActiveByIdAndUserId(2L, 1L)).willReturn(Optional.of(address));

        // when
        UpdateUserAddressResponse response = userAddressService.updateUserAddress(1L, 2L, updateRequest(false));

        // then
        assertThat(response.place()).isEqualTo("회사");
        assertThat(response.address()).isEqualTo("서울시 중구");
        assertThat(response.isDefault()).isFalse();
        then(userAddressRepository).should(never()).findDefaultByUserId(anyLong());
    }

    @Test
    void 일반_배송지를_기본으로_바꾸면_기존_기본_배송지는_일반_배송지가_된다() {
        // given
        User user = mock(User.class);
        UserAddress target = UserAddress.of(user, "회사", "홍길동", "010-1234-5678", "12345", "서울시", false);
        UserAddress oldDefault = UserAddress.of(user, "집", "홍길동", "010-1234-5678", "12345", "서울시", true);
        given(userAddressRepository.findActiveByIdAndUserId(2L, 1L)).willReturn(Optional.of(target));
        given(userAddressRepository.findDefaultByUserId(1L)).willReturn(Optional.of(oldDefault));

        // when
        UpdateUserAddressResponse response = userAddressService.updateUserAddress(1L, 2L, updateRequest(true));

        // then
        assertThat(response.isDefault()).isTrue();
        assertThat(oldDefault.isDefault()).isFalse();
    }

    @Test
    void 기본_배송지를_일반으로_바꾸면_DEFAULT_ADDRESS_REQUIRED가_발생한다() {
        // given
        UserAddress address = UserAddress.of(mock(User.class), "집", "홍길동", "010-1234-5678", "12345", "서울시", true);
        given(userAddressRepository.findActiveByIdAndUserId(2L, 1L)).willReturn(Optional.of(address));

        // when
        // then
        assertThatThrownBy(() -> userAddressService.updateUserAddress(1L, 2L, updateRequest(false)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DEFAULT_ADDRESS_REQUIRED));
        assertThat(address.getPlace()).isEqualTo("집");
    }

    @Test
    void 본인_배송지가_아니면_USER_ADDRESS_NOT_FOUND가_발생한다() {
        // given
        given(userAddressRepository.findActiveByIdAndUserId(2L, 1L)).willReturn(Optional.empty());

        // when
        // then
        assertThatThrownBy(() -> userAddressService.updateUserAddress(1L, 2L, updateRequest(false)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USER_ADDRESS_NOT_FOUND));
    }

    private UpdateUserAddressRequest updateRequest(boolean isDefault) {
        return new UpdateUserAddressRequest("회사", "김철수", "010-9876-5432", "54321", "서울시 중구", isDefault);
    }


}