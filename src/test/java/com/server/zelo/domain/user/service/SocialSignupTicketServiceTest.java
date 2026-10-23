package com.server.zelo.domain.user.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.entity.SocialProvider;
import com.server.zelo.domain.user.repository.SocialSignupTicketRepository;
import com.server.zelo.domain.user.social.SocialProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class SocialSignupTicketServiceTest {

    private static final SocialProfile PROFILE =
            SocialProfile.of(SocialProvider.GOOGLE, "1234567890", "jieun@gmail.com", true, "지은");

    @InjectMocks
    private SocialSignupTicketService socialSignupTicketService;

    @Mock
    private SocialSignupTicketRepository socialSignupTicketRepository;

    // JSON 변환이 실제로 왕복되는지 확인하려고 mock이 아닌 실제 변환기를 씀
    @Spy
    private JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void 발급하면_번호표를_반환하고_같은_번호표로_프로필을_저장한다() {
        // when
        String ticket = socialSignupTicketService.issue(PROFILE);

        // then
        ArgumentCaptor<String> savedTicket = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> savedJson = ArgumentCaptor.forClass(String.class);
        then(socialSignupTicketRepository).should().save(savedTicket.capture(), savedJson.capture());
        assertThat(savedTicket.getValue()).isEqualTo(ticket);
        assertThat(jsonMapper.readValue(savedJson.getValue(), SocialProfile.class)).isEqualTo(PROFILE);
    }

    @Test
    void 번호표는_URL에_안전한_43자_문자열이다() {
        // when
        String ticket = socialSignupTicketService.issue(PROFILE);

        // then
        // 32바이트 난수를 패딩 없는 URL-safe Base64로 바꾸면 43자가 됨
        assertThat(ticket).matches("^[A-Za-z0-9_-]{43}$");
    }

    @Test
    void 발급할_때마다_서로_다른_번호표가_나온다() {
        // when
        String first = socialSignupTicketService.issue(PROFILE);
        String second = socialSignupTicketService.issue(PROFILE);

        // then
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void 소비하면_저장했던_프로필을_그대로_복원한다() {
        // given
        String json = jsonMapper.writeValueAsString(PROFILE);
        given(socialSignupTicketRepository.consume("ticket")).willReturn(Optional.of(json));

        // when
        SocialProfile result = socialSignupTicketService.consume("ticket");

        // then
        assertThat(result).isEqualTo(PROFILE);
        assertThat(result.provider()).isEqualTo(SocialProvider.GOOGLE);
        assertThat(result.providerId()).isEqualTo("1234567890");
        assertThat(result.email()).isEqualTo("jieun@gmail.com");
        assertThat(result.emailVerified()).isTrue();
        assertThat(result.name()).isEqualTo("지은");
    }

    @Test
    void 만료되었거나_없는_번호표를_소비하면_INVALID_SIGNUP_TOKEN_예외가_발생한다() {
        // given
        given(socialSignupTicketRepository.consume("expired")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> socialSignupTicketService.consume("expired"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_SIGNUP_TOKEN);
    }

    @Test
    void 소비할_때_받은_번호표를_그대로_저장소에_전달한다() {
        // given
        // 스텁을 설정하는 중에 스파이를 호출하면 Mockito가 헷갈려서 JSON을 먼저 만들어 둠
        String json = jsonMapper.writeValueAsString(PROFILE);
        given(socialSignupTicketRepository.consume(anyString())).willReturn(Optional.of(json));

        // when
        socialSignupTicketService.consume("ticket-value");

        // then
        then(socialSignupTicketRepository).should().consume("ticket-value");
    }
}
