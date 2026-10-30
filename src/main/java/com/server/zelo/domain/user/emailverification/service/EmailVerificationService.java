package com.server.zelo.domain.user.emailverification.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.emailverification.entity.EmailVerificationPurpose;
import com.server.zelo.domain.user.emailverification.repository.EmailVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final EmailVerificationRepository emailVerificationRepository;

    public void consumeTicket(String email, String emailVerificationTicket, EmailVerificationPurpose purpose) {
        if (!emailVerificationRepository.consumeTicket(emailVerificationTicket, purpose, email)) {
            throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
        }

    }
}
