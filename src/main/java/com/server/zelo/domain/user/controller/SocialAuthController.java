package com.server.zelo.domain.user.controller;

import com.server.zelo.domain.user.service.SocialCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SocialAuthController {
    private final SocialCommandService socialCommandService;
}
