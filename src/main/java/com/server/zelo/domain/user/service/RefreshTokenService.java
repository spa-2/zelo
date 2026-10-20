package com.server.zelo.domain.user.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;

    public void saveRefreshToken(Long userId, String refreshToken) {
        refreshTokenRepository.save(userId, refreshToken);
    }

    // 이미 지워진 토큰이어도 성공으로 봄(로그아웃을 여러 번 눌러도 결과가 같게)
    public void deleteRefreshToken(Long userId) {
        try {
            refreshTokenRepository.delete(userId);
        } catch (DataAccessException e) {
            // Redis 장애로 못 지웠는데 성공 응답을 주면 사용자는 로그아웃된 줄 알지만 토큰이 살아있어서 실패로 알림
            log.error("리프레시 토큰 삭제 실패 userId={}", userId, e);
            throw new BusinessException(ErrorCode.TOKEN_REVOKE_FAILED);
        }
    }
}
