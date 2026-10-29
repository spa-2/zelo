package com.server.zelo.domain.user.emailverification.service;

import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.RateLimitExceededException;
import com.server.zelo.common.mail.EmailSender;
import com.server.zelo.domain.user.emailverification.entity.EmailVerificationPurpose;
import com.server.zelo.domain.user.emailverification.repository.EmailVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class EmailVerificationCommandService {

    private static final int CODE_LENGTH = 6; // 인증 코드 자릿수
    private static final int MAX_IP_COUNT = 20; // 한 ip가 시간당 보낼 수 있는 횟수
    private static final int MAX_SEND_COUNT = 5; // 윈도우 동안 최대 발송 횟수
    private static final Duration CODE_TTL = Duration.ofMinutes(5); // 코드 유효 시간
    private static final Duration COOLDOWN = Duration.ofSeconds(60); // 같은 이메일 재발송 대기 시간
    private static final Duration SEND_WINDOW = Duration.ofHours(1); // 발송 횟수를 세는 시간
    private static final Duration IP_BLOCK = Duration.ofMinutes(30); // 동일 ip 차단 시간
    private static final Duration SEND_BLOCK = Duration.ofMinutes(10); // 횟수 초과 시 차단 시간
    private static final int CODE_BOUND = (int) Math.pow(10, CODE_LENGTH);

    private static final String TITLE = "[zelo] 이메일 검증 요청";
    private static final String BODY_FORMAT = "당신의 검증 코드는 %s 입니다. %d분 이내에 입력해 주세요.";

    private final EmailSender emailSender;
    private final SecureRandom secureRandom;
    private final EmailVerificationRepository emailVerificationRepository;

    public void sendEmail(String email, EmailVerificationPurpose purpose, String remoteAddr) {

        // ip카운트 (1시간동안 같은 IP에서 여러번 메일 요청을 하면 플러딩으로 의심하여 제한)
        // 가장 넓은 범위라서 먼저 검사하고, 차단된 뒤에도 요청마다 세어서 계속 차단 상태를 유지한다
        long ipCount = emailVerificationRepository.increaseIpCount(remoteAddr, MAX_IP_COUNT, SEND_WINDOW, IP_BLOCK);
        if (ipCount > MAX_IP_COUNT) {
            throw new RateLimitExceededException(ErrorCode.TOO_MANY_MAIL_SEND, IP_BLOCK.toSeconds());
        }

        // 쿨다운 (N초 이내 메일을 보낸 이력이 있으면 보내지 않는다. 연타해도 아래 시간당 횟수가 소모되지 않게 먼저 검사)
        if (!emailVerificationRepository.acquireCooldown(email, purpose, COOLDOWN)) {
            throw new RateLimitExceededException(ErrorCode.TOO_MANY_MAIL_SEND, COOLDOWN.toSeconds());
        }

        // 윈도우 동안 몇번 메일을 발송했는지 확인하여 N번을 넘으면 보내지 않는다.
        long count = emailVerificationRepository.increaseSendCount(email, MAX_SEND_COUNT, SEND_WINDOW, SEND_BLOCK);
        if (count > MAX_SEND_COUNT) {
            throw new RateLimitExceededException(ErrorCode.TOO_MANY_MAIL_SEND, SEND_BLOCK.toSeconds());
        }

        String code = generate();
        emailVerificationRepository.saveCode(email, purpose, code, CODE_TTL);

        // 코드 저장 성공 후 이메일 발송
        emailSender.send(email, TITLE, BODY_FORMAT.formatted(code, CODE_TTL.toMinutes()));
    }

    private String generate() {
        return String.format("%0" + CODE_LENGTH + "d", secureRandom.nextInt(CODE_BOUND));
    }
}
