package com.server.zelo.domain.user.service;

import com.server.zelo.domain.user.repository.SocialSignupTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SocialSignupTicketService {
    private final SocialSignupTicketRepository socialSignupTicketRepository;
}
