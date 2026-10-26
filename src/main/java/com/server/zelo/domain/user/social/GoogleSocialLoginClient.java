package com.server.zelo.domain.user.social;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.dto.social.SocialTokenResponse;
import com.server.zelo.domain.user.dto.social.GoogleUserInfoResponse;
import com.server.zelo.domain.user.entity.SocialProvider;
import com.server.zelo.domain.user.social.config.GoogleOAuthProperties;
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
public class GoogleSocialLoginClient implements SocialLoginClient {

    private final RestClient googleSocialRestClient;
    private final GoogleOAuthProperties googleOAuthProperties;

    @Override
    public SocialProvider getProvider() {
        return SocialProvider.GOOGLE;
    }

    @Override
    public SocialProfile getProfile(String code, String redirectUri, String codeVerifier) {
        // 1. 구글로부터 Access Token 받아오기
        SocialTokenResponse tokenResponse = fetchGoogleToken(code, redirectUri, codeVerifier);

        // 2. Access Token을 이용해 구글 사용자 정보 가져오기
        GoogleUserInfoResponse userInfoResponse = getUserInfo(tokenResponse.accessToken());

        // 이메일 검증 여부가 null이면 false를 반환
        return SocialProfile.of(
                getProvider(),
                userInfoResponse.sub(),
                userInfoResponse.email(),
                userInfoResponse.isEmailVerified(),
                userInfoResponse.name());
    }

    // 구글 OAuth 서버와 통신하여 Access Token을 발급받음
    private SocialTokenResponse fetchGoogleToken(String code, String redirectUri, String codeVerifier) {
        try {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("code", code);
            params.add("client_id", googleOAuthProperties.clientId());
            params.add("client_secret", googleOAuthProperties.clientSecret());
            params.add("redirect_uri", redirectUri);
            params.add("grant_type", "authorization_code");

            if (codeVerifier != null) {
                params.add("code_verifier", codeVerifier);
            }

            SocialTokenResponse tokenResponse = googleSocialRestClient.post()
                    .uri(googleOAuthProperties.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(params)
                    .retrieve()
                    .body(SocialTokenResponse.class);

            if (tokenResponse == null || tokenResponse.accessToken() == null || tokenResponse.accessToken().isBlank()) {
                throw new BusinessException(ErrorCode.SOCIAL_AUTH_FAILED);
            }

            return tokenResponse;

        } catch (HttpServerErrorException | ResourceAccessException e) {
            // 5xx는 구글 쪽 장애라 사용자 인증 실패(401)와 구분해야 함
            log.error("구글 토큰 서버 오류 또는 연결 실패", e);
            throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);
        } catch (RestClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();

            // 1. 잘못된 설정 파일 오류 (invalid_client) 캐치
            if (responseBody.contains("invalid_client")) {
                log.error("🚨 [설정오류] 구글 client_id 또는 client_secret이 올바르지 않음, 응답: {}", responseBody);
                throw new BusinessException(ErrorCode.SOCIAL_AUTH_FAILED);
            }

            // 2. 5xx 에러 (구글 서버 장애)와 4xx 에러 (사용자 인증 실패) 분기
            if (e.getStatusCode().is5xxServerError()) {
                log.error("구글 토큰 서버 내부 오류 (5xx), 응답: {}", responseBody);
                throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);
            }

            log.error("구글 토큰 교환 실패 (4xx), 상태코드: {}, 응답: {}", e.getStatusCode(), responseBody);
            throw new BusinessException(ErrorCode.SOCIAL_AUTH_FAILED);
        } catch (RestClientException e) {
            // 응답 본문을 해석하지 못한 경우 등 (그대로 두면 500)
            log.error("구글 토큰 응답 처리 실패", e);
            throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);
        }
    }

    // Access Token을 사용하여 구글 사용자 정보를 조회
    private GoogleUserInfoResponse getUserInfo(String accessToken) {
        try {
            GoogleUserInfoResponse userInfoResponse = googleSocialRestClient.get()
                    .uri(googleOAuthProperties.userInfoUri())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(GoogleUserInfoResponse.class);

            if (userInfoResponse == null || userInfoResponse.sub() == null || userInfoResponse.sub().isBlank()) {
                throw new BusinessException(ErrorCode.SOCIAL_AUTH_FAILED);
            }

            return userInfoResponse;

        } catch (HttpClientErrorException e) {
            log.error("구글 사용자 정보 조회 실패, 인증 오류, 응답: {}", e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.SOCIAL_AUTH_FAILED);
        } catch (RestClientException e) {
            log.error("구글 사용자 정보 서버 오류, 연결 실패 또는 응답 처리 실패", e);
            throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);
        }
    }
}
