package com.server.zelo.domain.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SocialCommandService {
    private final SocialAccountService socialAccountService;
    private final SocialSignupTicketService socialSignupTicketService;
}
