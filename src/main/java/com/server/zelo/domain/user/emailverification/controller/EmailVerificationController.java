package com.server.zelo.domain.user.emailverification.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.domain.user.emailverification.dto.SendEmailRequest;
import com.server.zelo.domain.user.emailverification.dto.ValidateEmailRequest;
import com.server.zelo.domain.user.emailverification.dto.ValidateEmailResponse;
import com.server.zelo.domain.user.emailverification.service.EmailVerificationCommandService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/email-verifications")
public class EmailVerificationController {

    private final EmailVerificationCommandService emailVerificationCommandService;

    @PostMapping
    public ResponseEntity<ApiResponse<Void>> sendEmail(
            @Valid @RequestBody SendEmailRequest request, HttpServletRequest httpServletRequest) {
        emailVerificationCommandService.sendEmail(request.email(),request.purpose() ,httpServletRequest.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<ValidateEmailResponse>> validateEmail(
            @Valid @RequestBody ValidateEmailRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(emailVerificationCommandService.validateEmail(request.email(), request.purpose(),request.code())));
    }

}
