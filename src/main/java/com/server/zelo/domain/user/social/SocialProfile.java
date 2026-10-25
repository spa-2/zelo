package com.server.zelo.domain.user.social;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.entity.SocialProvider;

// 제공자마다 응답 모양이 달라서 공통 모양으로 변경하기 위함
public record SocialProfile(
        SocialProvider provider,
        String providerId,
        // Todo email이 50자 넘어간다면 문제가 있음
        String email,
        boolean emailVerified,
        String name
){
    private static final int NAME_MAX_LENGTH = 50;

    public static SocialProfile of(SocialProvider provider, String providerId, String email, boolean emailVerified, String name){
        return new SocialProfile(provider, providerId, email, emailVerified, name);
    }

   public void validateForSignup(){
       if (email() == null || email().isBlank() || !emailVerified()) {
           throw new BusinessException(ErrorCode.SOCIAL_EMAIL_REQUIRED);
       }
   }

   // 최초 가입 시 이름이 없을 경우 소셜 로그인 및 주소입력 후 db에 저장 시 500에러가 터짐
   // 그러나 redis에는 이미 이름이 null인 상태로 저장이 되다 보니 사용자가 아무리 회원가입을 시도해도 에러만 터지는 심각한 상황
   // 만약 최초 가입 시 이름이 존재하지 않는다면 이메일의 앞부분을 가져옴
   public String displayName() {
        String base = (name == null || name.isBlank())
                ? email.substring(0, email.indexOf('@'))
                : name.strip();

        return base.length() > NAME_MAX_LENGTH
                ? base.substring(0, NAME_MAX_LENGTH)
                : base;
    }
}
