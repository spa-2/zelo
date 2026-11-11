package com.server.zelo.domain.user.emailverification.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.emailverification.entity.EmailVerificationPurpose;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
// purpose 를 CommandService의 sendMail에서 구분하는 로직을 추가하기 보다 앞으로 확장 가능성을 고려해봤을 때 중간자를 배치하는 게 나을거라 판단
public class EmailVerificationSendCommandService {

    private final UserService userService;
    private final EmailVerificationCommandService emailVerificationCommandService;

    public void sendEmail(String email, EmailVerificationPurpose purpose, String remoteAddr) {

        // purpose가 늘어남에 따라 컴파일 시점에 알기 위해서 switch식 사용
        boolean compileCheck = switch (purpose) {
            case SIGNUP -> {
               yield true;
            }
            case PASSWORD_RESET -> {
                emailVerificationCommandService.validatePasswordResetLimit(remoteAddr);
                // 가입이 되지 않는 사용자면 예외를 전달, 이미 회원가입 시 가입 여부를 알 수 있어 안내를 한다.
                User user = userService.getUserByEmail(email);

                // 패스워드가 없다면 소셜 계정이라서 예외를 던짐
                if (user.isPasswordNull()) {
                    throw new BusinessException(ErrorCode.SOCIAL_ACCOUNT_USER);
                }
                yield  true;
            }
        };

        emailVerificationCommandService.sendEmail(email, purpose, remoteAddr);
    }
}
