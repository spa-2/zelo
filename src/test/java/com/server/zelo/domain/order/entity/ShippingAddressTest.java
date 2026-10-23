package com.server.zelo.domain.order.entity;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @ParameterizedTest(name = "{0} 누락")
    @MethodSource("addressesWithMissingField")
    void 배송_필수_필드가_null이면_INVALID_INPUT_예외가_발생한다(
            String missingField, String recipientName, String recipientPhone, String zipCode, String address) {
        // given
        // when
        // then
        assertThatThrownBy(() -> ShippingAddress.of(recipientName, recipientPhone, zipCode, address))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    private static Stream<Arguments> addressesWithMissingField() {
        return Stream.of(
                Arguments.of("받는 사람 이름", null, "010-1234-5678", "06236", "서울특별시 강남구 테헤란로 123"),
                Arguments.of("받는 사람 연락처", "홍길동", null, "06236", "서울특별시 강남구 테헤란로 123"),
                Arguments.of("우편번호", "홍길동", "010-1234-5678", null, "서울특별시 강남구 테헤란로 123"),
                Arguments.of("주소", "홍길동", "010-1234-5678", "06236", null)
        );
    }

    private static Stream<Arguments> validAddresses() {
        return Stream.of(
                Arguments.of("홍길동", "010-1234-5678", "06236", "서울특별시 강남구 테헤란로 123, 101동 202호"),
                Arguments.of("김".repeat(50), "1".repeat(20), "0".repeat(10), "가".repeat(200))
        );
    }
}
