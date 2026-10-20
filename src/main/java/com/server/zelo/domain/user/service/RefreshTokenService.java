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

    // 판단 순서: 현재 토큰과 일치 → 다른 기기 로그인으로 낡아진 토큰 → 저장된 토큰 없음 → 그 외(교체된 토큰의 재사용)
    public void rotateToken(Long userId, String presentedRefreshToken, String newRefreshToken) {
        // 현재 redis에 저장된 리프레시 토큰과 사용자가 보낸 토큰이 일치하다면
        if (refreshTokenRepository.matches(userId, presentedRefreshToken)) {
            // 엑세스와 리프레스 토큰을 모두 갱신
            refreshTokenRepository.rotate(userId, newRefreshToken);
            return;
        }

        // 현재 redis에 저장된 리프레시 토큰은 아니지만 superseded 토큰이면 본인이 다른 기기에서 로그인한 것이라서 새 기기의 세션은 지키고 안내만 함
        if (refreshTokenRepository.isSuperseded(presentedRefreshToken)) {
            throw new BusinessException(ErrorCode.LOGGED_IN_ELSEWHERE);
        }

        // 로그아웃했거나 만료된 경우거에는 그냥 넘어감
        if (!refreshTokenRepository.exists(userId)) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        // 이미 교체된 토큰이 다시 들어온 경우로 탈취 가능성이 높기 때문에 현재 로그인한 사용자의 토큰을 삭제함(재 로그인 유도)
        log.warn("리프레시 토큰 재사용 감지 userId={}", userId);
        refreshTokenRepository.delete(userId);
        throw new BusinessException(ErrorCode.TOKEN_REUSED);
    }
}
