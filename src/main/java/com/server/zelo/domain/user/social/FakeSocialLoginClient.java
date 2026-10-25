package com.server.zelo.domain.user.social;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.entity.SocialProvider;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "zelo.social.mode", havingValue = "fake")
public class FakeSocialLoginClient implements SocialLoginClient {

    // 실제로 존재할 수 있는 도메인(fake.com)이 아니라 예약된 example.com 하위 도메인을 써서 실수로 메일이 나가는 일을 막음
    private static final String FAKE_EMAIL_DOMAIN = "@fake.example.com";

    // 운영상에서 켜지면 누구나 로그인을 할 수 있어 부팅 시 경고를 남김
    @PostConstruct
    void warn() {
        log.warn("FakeSocialLoginClient 활성화, 운영 환경이라면 절대 사용 금지");
    }

    @Override
    public SocialProvider getProvider() {
        return SocialProvider.GOOGLE;
    }

    @Override
    // code의 값에 따라 결과를 다르게 동작시키고, 원래 code는 1회성 이지만 테스트에서는 code를 통해 유저를 만들어 유저 고유 식별자로 사용
    public SocialProfile getProfile(String code, String redirectUri, String codeVerifier) {

        // fake- 로 시작하면 정상 프로필 응답, 이름과 이메일, providerId는 code에서 가져와서 사용
        if (code.startsWith("fake-")) {
            return SocialProfile.of(
                    SocialProvider.GOOGLE, code ,code + FAKE_EMAIL_DOMAIN,true, code );
        }

        switch (code) {
            case "no-email":
                // 이메일 없는 프로필 번환
                return SocialProfile.of(
                        SocialProvider.GOOGLE, code ,null, true, code);

            case "unverified":
                // 인증되지 않은 프로필 반환
                return SocialProfile.of(
                        SocialProvider.GOOGLE, code ,code + FAKE_EMAIL_DOMAIN, false, code);

            case "no-name":
                // 이름이 없는 프로필 반환 (가입 시 이메일 앞부분을 이름으로 쓰는지 확인용)
                return SocialProfile.of(
                        SocialProvider.GOOGLE, code, code + FAKE_EMAIL_DOMAIN, true, null);

            case "long-name":
                // 이름이 50자를 넘는 프로필 반환 (users.name 길이 제한 확인용)
                // 같은 code는 항상 같은 사람이어야 해서 매번 달라지는 값(UUID)이 아니라 고정된 값을 씀
                // 이메일이 없으면 가입 검증에서 먼저 거부되어 이름 보정까지 도달하지 못해서 이메일도 함께 줌
                return SocialProfile.of(
                        SocialProvider.GOOGLE, code, code + FAKE_EMAIL_DOMAIN, true, "가".repeat(60));

            case "rejected":
                // SOCIAL_AUTH_FAILED 예외 발생
                throw new BusinessException(ErrorCode.SOCIAL_AUTH_FAILED);

            case "provider-down":
                // SOCIAL_PROVIDER_UNAVAILABLE 예외 발생
                throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);

            default:
                // 알 수 없는 code에 대한 인증 실패 예외 발생
                throw new BusinessException(ErrorCode.SOCIAL_AUTH_FAILED);
        }

    }
}
