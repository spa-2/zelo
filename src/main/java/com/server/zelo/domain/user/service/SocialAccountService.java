package com.server.zelo.domain.user.service;

import com.server.zelo.domain.user.repository.SocialAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SocialAccountService {
    private final SocialAccountRepository socialAccountRepository;
}
