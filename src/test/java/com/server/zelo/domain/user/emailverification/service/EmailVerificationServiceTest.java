package com.server.zelo.domain.user.emailverification.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.emailverification.entity.EmailVerificationPurpose;
import com.server.zelo.domain.user.emailverification.repository.EmailVerificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    private static final String EMAIL = "jieun@example.com";
    private static final String TICKET = "A".repeat(43);
    private static final EmailVerificationPurpose PURPOSE = EmailVerificationPurpose.SIGNUP;

    @InjectMocks
    private EmailVerificationService emailVerificationService;

    @Mock
    private EmailVerificationRepository emailVerificationRepository;

    @Test
    void 티켓이_유효하면_예외가_발생하지_않는다() {
        // given
        given(emailVerificationRepository.consumeTicket(TICKET, PURPOSE, EMAIL)).willReturn(true);

        // when & then
        assertThatCode(() -> emailVerificationService.consumeTicket(EMAIL, TICKET, PURPOSE))
                .doesNotThrowAnyException();
    }

    @Test
    void 티켓이_없거나_만료됐거나_이메일이_다르면_이메일_인증_필요_예외가_발생한다() {
        // given
        given(emailVerificationRepository.consumeTicket(TICKET, PURPOSE, EMAIL)).willReturn(false);

        // when & then 원인을 구분하지 않고 같은 예외로 응답한다
        assertThatThrownBy(() -> emailVerificationService.consumeTicket(EMAIL, TICKET, PURPOSE))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.EMAIL_VERIFICATION_REQUIRED));
    }
}
