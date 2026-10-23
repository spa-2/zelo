package com.server.zelo.domain.order.entity;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ShippingAddressTest {

    @ParameterizedTest
    @MethodSource("validAddresses")
    void 배송지_스냅샷은_전달받은_네_가지_값을_손실_없이_보존한다(
            String recipientName, String recipientPhone, String zipCode, String address) {
        // given
        // when
        ShippingAddress shippingAddress = ShippingAddress.of(recipientName, recipientPhone, zipCode, address);

        // then
        assertThat(shippingAddress.getRecipientName()).isEqualTo(recipientName);
        assertThat(shippingAddress.getRecipientPhone()).isEqualTo(recipientPhone);
        assertThat(shippingAddress.getZipCode()).isEqualTo(zipCode);
        assertThat(shippingAddress.getAddress()).isEqualTo(address);
    }

    private static Stream<Arguments> validAddresses() {
        return Stream.of(
                Arguments.of("홍길동", "010-1234-5678", "06236", "서울특별시 강남구 테헤란로 123, 101동 202호"),
                Arguments.of("김".repeat(50), "1".repeat(20), "0".repeat(10), "가".repeat(200))
        );
    }
}
