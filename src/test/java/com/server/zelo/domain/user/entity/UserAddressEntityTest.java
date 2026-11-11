package com.server.zelo.domain.user.entity;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class UserAddressEntityTest {

    @Test
    void 기본_배송지를_해제하면_일반_배송지가_된다() {
        // given
        UserAddress address = UserAddress.of(mock(User.class), "집", "홍길동", "010-1234-5678", "12345", "서울시", true);

        // when
        address.releaseDefault();

        // then
        assertThat(address.isDefault()).isFalse();
    }

    @Test
    void 일반_배송지는_삭제할_수_있다() {
        // given
        UserAddress address = UserAddress.of(mock(User.class), "회사", "홍길동", "010-1234-5678", "12345", "서울시", false);

        // when
        // then
        assertThatCode(address::validateDeletable).doesNotThrowAnyException();
    }

    @Test
    void 기본_배송지를_삭제하면_DEFAULT_ADDRESS_REQUIRED가_발생한다() {
        // given
        UserAddress address = UserAddress.of(mock(User.class), "집", "홍길동", "010-1234-5678", "12345", "서울시", true);

        // when
        // then
        assertThatThrownBy(address::validateDeletable)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DEFAULT_ADDRESS_REQUIRED));
    }
}