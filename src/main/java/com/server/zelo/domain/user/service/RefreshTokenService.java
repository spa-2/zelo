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

    // 판단 순서: 현재 토큰과 일치(교체) → 다른 기기 로그인으로 낡아진 토큰 → 저장된 토큰 없음 → 방금 교체된 토큰 → 그 외(교체된 토큰의 재사용)
    public void rotateToken(Long userId, String receivedRefreshToken, String newRefreshToken) {
        // 확인과 교체를 한 번에 처리해서 그 사이에 로그인이나 로그아웃이 끼어들어도 덮어쓰지 않음
        if (refreshTokenRepository.rotateIfMatches(userId, receivedRefreshToken, newRefreshToken)) {
            return;
        }

        // 본인이 다른 기기에서 로그인한 것이라서 새 기기의 세션은 지키고 안내만 함
        if (refreshTokenRepository.isSuperseded(receivedRefreshToken)) {
            throw new BusinessException(ErrorCode.LOGGED_IN_ELSEWHERE);
        }

        // 로그아웃했거나 재사용 감지로 삭제가 된 토큰으로 재발급 요청을 한 경우
        if (!refreshTokenRepository.exists(userId)) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        // 동시 요청이나 응답 유실 후 재시도일 수 있어서 세션은 지키고 새 토큰도 내주지 않음 (내주면 탈취된 토큰으로도 새 토큰을 받게 됨)
        if (refreshTokenRepository.isRotated(receivedRefreshToken)) {
            log.warn("방금 교체된 리프레시 토큰으로 재요청 userId={}", userId);
            throw new BusinessException(ErrorCode.TOKEN_ALREADY_ROTATED);
        }

        // 로그인으로 말려난 (superseded)도 아니고, 재발급 후 밀려나서 방금 교체된 (rotated)도 아닌 토큰이 들어온 경우로 탈취 가능성이 높기 때문에 현재 로그인한 사용자의 토큰도 삭제함(재 로그인 유도)
        log.warn("리프레시 토큰 재사용 감지 userId={}", userId);
        refreshTokenRepository.delete(userId);
        throw new BusinessException(ErrorCode.TOKEN_REUSED);
    }
}
