package com.server.zelo.domain.user.emailverification.service;

import com.server.zelo.common.exception.RateLimitExceededException;
import com.server.zelo.common.mail.EmailSender;
import com.server.zelo.domain.user.emailverification.entity.EmailVerificationPurpose;
import com.server.zelo.domain.user.emailverification.repository.EmailVerificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.SecureRandom;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class EmailVerificationCommandServiceTest {

    private static final String EMAIL = "jieun@example.com";
    private static final String IP = "203.0.113.7";
    private static final EmailVerificationPurpose PURPOSE = EmailVerificationPurpose.SIGNUP;
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final Duration CODE_TTL = Duration.ofMinutes(5);

    @InjectMocks
    private EmailVerificationCommandService emailVerificationCommandService;

    @Mock
    private EmailSender emailSender;

    @Mock
    private SecureRandom secureRandom;

    @Mock
    private EmailVerificationRepository emailVerificationRepository;

    @Test
    void 정상_요청이면_코드를_저장한_뒤_메일을_발송한다() {
        // given
        givenIpCount(1);
        givenCooldownAcquired();
        givenSendCount(1);
        given(secureRandom.nextInt(1_000_000)).willReturn(123456);

        // when
        emailVerificationCommandService.sendEmail(EMAIL, PURPOSE, IP);

        // then 코드가 저장된 뒤에 메일이 나가야 함(저장에 실패했는데 메일만 나가면 안 됨)
        InOrder inOrder = inOrder(emailVerificationRepository, emailSender);
        inOrder.verify(emailVerificationRepository).saveCode(EMAIL, PURPOSE, "123456", CODE_TTL);
        inOrder.verify(emailSender).send(anyString(), anyString(), anyString());
    }

    @Test
    void 인증_코드는_6자리로_앞을_0으로_채우고_메일_본문에_포함된다() {
        // given
        givenIpCount(1);
        givenCooldownAcquired();
        givenSendCount(1);
        given(secureRandom.nextInt(1_000_000)).willReturn(42);

        // when
        emailVerificationCommandService.sendEmail(EMAIL, PURPOSE, IP);

        // then
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        then(emailSender).should().send(org.mockito.ArgumentMatchers.eq(EMAIL), anyString(), body.capture());
        then(emailVerificationRepository).should().saveCode(EMAIL, PURPOSE, "000042", CODE_TTL);
        assertThat(body.getValue()).contains("000042").contains("5분");
    }

    @Test
    void 쿨다운_중이면_횟수_증가_코드_저장_발송을_모두_하지_않는다() {
        // given
        givenIpCount(1);
        given(emailVerificationRepository.acquireCooldown(EMAIL, PURPOSE, COOLDOWN)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> emailVerificationCommandService.sendEmail(EMAIL, PURPOSE, IP))
                .isInstanceOf(RateLimitExceededException.class)
                .extracting(e -> ((RateLimitExceededException) e).getRetryAfterSeconds())
                .isEqualTo(60L);

        then(emailVerificationRepository).should(never()).increaseSendCount(anyString(), anyInt(), any(), any());
        then(emailVerificationRepository).should(never()).saveCode(anyString(), any(), anyString(), any());
        then(emailSender).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 10, 20})
    void IP_요청_횟수가_20회_이하이면_통과한다(long ipCount) {
        // given
        givenIpCount(ipCount);
        givenCooldownAcquired();
        givenSendCount(1);
        given(secureRandom.nextInt(1_000_000)).willReturn(1);

        // when & then
        assertThatCode(() -> emailVerificationCommandService.sendEmail(EMAIL, PURPOSE, IP))
                .doesNotThrowAnyException();
    }

    @Test
    void IP_요청_횟수가_20회를_넘으면_쿨다운_횟수_저장_발송을_모두_하지_않는다() {
        // given
        givenIpCount(21);

        // when & then
        assertThatThrownBy(() -> emailVerificationCommandService.sendEmail(EMAIL, PURPOSE, IP))
                .isInstanceOf(RateLimitExceededException.class)
                .extracting(e -> ((RateLimitExceededException) e).getRetryAfterSeconds())
                .isEqualTo(1800L); // ip 차단 시간(30분)

        then(emailVerificationRepository).should(never()).acquireCooldown(anyString(), any(), any());
        then(emailVerificationRepository).should(never()).increaseSendCount(anyString(), anyInt(), any(), any());
        then(emailVerificationRepository).should(never()).saveCode(anyString(), any(), anyString(), any());
        then(emailSender).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void 발송_횟수가_5회_이하이면_통과한다(long count) {
        // given
        givenIpCount(1);
        givenCooldownAcquired();
        givenSendCount(count);
        given(secureRandom.nextInt(1_000_000)).willReturn(1);

        // when & then
        assertThatCode(() -> emailVerificationCommandService.sendEmail(EMAIL, PURPOSE, IP))
                .doesNotThrowAnyException();
    }

    @Test
    void 발송_횟수가_5회를_넘으면_코드_저장과_발송을_하지_않는다() {
        // given
        givenIpCount(1);
        givenCooldownAcquired();
        givenSendCount(6);

        // when & then
        assertThatThrownBy(() -> emailVerificationCommandService.sendEmail(EMAIL, PURPOSE, IP))
                .isInstanceOf(RateLimitExceededException.class)
                .extracting(e -> ((RateLimitExceededException) e).getRetryAfterSeconds())
                .isEqualTo(600L); // 차단 시간(10분)

        then(emailVerificationRepository).should(never()).saveCode(anyString(), any(), anyString(), any());
        then(emailSender).shouldHaveNoInteractions();
    }

    private void givenIpCount(long count) {
        given(emailVerificationRepository.increaseIpCount(
                IP, 20, Duration.ofHours(1), Duration.ofMinutes(30))).willReturn(count);
    }

    private void givenCooldownAcquired() {
        given(emailVerificationRepository.acquireCooldown(EMAIL, PURPOSE, COOLDOWN)).willReturn(true);
    }

    private void givenSendCount(long count) {
        given(emailVerificationRepository.increaseSendCount(
                EMAIL, 5, Duration.ofHours(1), Duration.ofMinutes(10))).willReturn(count);
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
