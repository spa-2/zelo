package com.server.zelo.domain.refund.dto;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.refund.dto.request.CreateRefundRequest;
import com.server.zelo.domain.refund.dto.request.RefundItemRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateRefundRequestTest {

    @Test
    void 환불_상품이_없으면_빈_맵을_돌려준다() {
        // when // then
        assertThat(new CreateRefundRequest("단순 변심", null).toCancelQuantities()).isEmpty();
    }

    @Test
    void 같은_주문_상품이_두_번_들어오면_예외가_발생한다() {
        // given
        CreateRefundRequest request = new CreateRefundRequest("단순 변심",
                List.of(new RefundItemRequest(1L, 1), new RefundItemRequest(1L, 2)));

        // when // then
        assertThatThrownBy(request::toCancelQuantities)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }
}