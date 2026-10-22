package com.server.zelo.domain.user.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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
}