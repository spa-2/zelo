package com.server.zelo.domain.user.social;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.dto.social.KakaoUserInfoResponse;
import com.server.zelo.domain.user.dto.social.SocialTokenResponse;
import com.server.zelo.domain.user.entity.SocialProvider;
import com.server.zelo.domain.user.social.config.KakaoOAuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.*;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "zelo.social.mode", havingValue = "real")
public class KakaoSocialLoginClient implements SocialLoginClient {

    private final RestClient kakaoSocialRestClient;
    private final KakaoOAuthProperties kakaoOAuthProperties;

    @Override
    public SocialProvider getProvider() {
        return SocialProvider.KAKAO;
    }

    @Override
    public SocialProfile getProfile(String code, String redirectUri, String codeVerifier) {
        SocialTokenResponse socialTokenResponse = fetchKakaoToken(code, redirectUri);
        KakaoUserInfoResponse userInfoResponse = getUserInfo(socialTokenResponse.accessToken());
        return SocialProfile.of(
                getProvider(),
                userInfoResponse.getProviderId(),
                userInfoResponse.getEmail(),
                userInfoResponse.isEmailVerified(),
                userInfoResponse.getNickname()
        );
    }

    private SocialTokenResponse fetchKakaoToken(String code, String redirectUri) {
        try {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("code", code);
            params.add("client_id", kakaoOAuthProperties.clientId());
            params.add("client_secret", kakaoOAuthProperties.clientSecret());
            params.add("redirect_uri", redirectUri);
            params.add("grant_type", "authorization_code");

            SocialTokenResponse tokenResponse = kakaoSocialRestClient.post()
                    .uri(kakaoOAuthProperties.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(params)
                    .retrieve()
                    .body(SocialTokenResponse.class);

            if (tokenResponse == null || tokenResponse.accessToken() == null || tokenResponse.accessToken().isBlank()) {
                throw new BusinessException(ErrorCode.SOCIAL_AUTH_FAILED);
            }

            return tokenResponse;

        } catch (HttpServerErrorException | ResourceAccessException e) {
            log.error("카카오 토큰 서버 오류 또는 연결 실패", e);
            throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);
        } catch (RestClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();

            if (responseBody.contains("invalid_client")) {
                log.error("🚨 [설정오류] 카카오 client_id 또는 client_secret이 올바르지 않음, 응답: {}", responseBody);
                throw new BusinessException(ErrorCode.SOCIAL_AUTH_FAILED);
            }

            if (e.getStatusCode().is5xxServerError()) {
                log.error("카카오 토큰 서버 내부 오류 (5xx), 응답: {}", responseBody);
                throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);
            }

            log.error("카카오 토큰 교환 실패 (4xx), 상태코드: {}, 응답: {}", e.getStatusCode(), responseBody);
            throw new BusinessException(ErrorCode.SOCIAL_AUTH_FAILED);
        } catch (RestClientException e) {
            // 응답 본문을 해석하지 못한 경우 등 (그대로 두면 500)
            log.error("카카오 토큰 응답 처리 실패", e);
            throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);
        }
    }

    private KakaoUserInfoResponse getUserInfo(String accessToken) {
        try {
            KakaoUserInfoResponse userInfoResponse = kakaoSocialRestClient.get()
                    .uri(kakaoOAuthProperties.userInfoUri())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(KakaoUserInfoResponse.class);

            if (userInfoResponse == null || userInfoResponse.id() == null || userInfoResponse.getProviderId().isBlank()) {
                throw new BusinessException(ErrorCode.SOCIAL_AUTH_FAILED);
            }

            return userInfoResponse;

        } catch (HttpClientErrorException e) {
            log.error("카카오 사용자 정보 조회 실패, 인증 오류, 응답: {}", e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.SOCIAL_AUTH_FAILED);
        } catch (RestClientException e) {
            log.error("카카오 사용자 정보 서버 오류, 연결 실패 또는 응답 처리 실패", e);
            throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);
        }
    }


}
