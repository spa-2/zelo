package com.server.zelo.domain.user.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.dto.response.GetUserAddressResponse;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

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
    void 기본_배송지를_돌려준다() {
        // given
        UserAddress address = UserAddress.of(mock(User.class), "집", "홍길동", "010-1234-5678", "12345", "서울시", true);
        given(userAddressRepository.findDefaultByUserId(1L)).willReturn(Optional.of(address));

        // when
        UserAddress result = userAddressService.getDefaultAddress(1L);

        // then
        assertThat(result).isSameAs(address);
    }

    @Test
    void 기본_배송지가_없으면_DEFAULT_ADDRESS_NOT_FOUND가_발생한다() {
        // given
        given(userAddressRepository.findDefaultByUserId(1L)).willReturn(Optional.empty());

        // when
        // then
        assertThatThrownBy(() -> userAddressService.getDefaultAddress(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DEFAULT_ADDRESS_NOT_FOUND));
    }
}