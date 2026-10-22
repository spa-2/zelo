package com.server.zelo.domain.user.social;

import com.server.zelo.domain.user.entity.SocialProvider;

// 제공자마다 응답 모양이 달라서 공통 모양으로 변경하기 위함
public record SocialProfile(
        SocialProvider provider,
        String providerId,
        String email,
        boolean emailVerified,
        String name
){
    public static SocialProfile of(SocialProvider provider, String providerId, String email, boolean emailVerified, String name){
        return new SocialProfile(provider, providerId, email, emailVerified, name);
    }
}
