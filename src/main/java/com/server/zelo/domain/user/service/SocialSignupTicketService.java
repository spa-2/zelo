package com.server.zelo.domain.user.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.repository.SocialSignupTicketRepository;
import com.server.zelo.domain.user.social.SocialProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SocialSignupTicketService {

    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();
    private final SocialSignupTicketRepository socialSignupTicketRepository;

    // 처음 소셜 로그인으로 왔다면 티켓을 발급해줌
    public String issue(SocialProfile profile){

        // 값을 추측할 수 없는 예측 불가능한 원본 토큰 생성
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        // URL로 전달 가능한 문자열 변환
        String socialSignupTicket = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        // profile을 json문자열로 변환
        String profileJson = objectMapper.writeValueAsString(profile);

        // redis에 30분간 저장
        socialSignupTicketRepository.save(socialSignupTicket, profileJson);

        return socialSignupTicket;
    }

    public SocialProfile consume(String socialSignupTicket){

        // 검색 후 삭제
        Optional<String> profileJson = socialSignupTicketRepository.consume(socialSignupTicket);

        // 이미 만료가 되었거나 존재하지 않는 티켓
        if (profileJson.isEmpty()){
            throw new BusinessException(ErrorCode.INVALID_SIGNUP_TOKEN);
        }

        return objectMapper.readValue(profileJson.get(), SocialProfile.class);
    }
}
