package com.server.zelo.domain.chat.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.common.auth.Auth;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.request.SendChatMessageRequest;
import com.server.zelo.domain.chat.service.ChatMessageOrchestrator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat/rooms/{roomId}/messages")
public class ChatMessageController {

    private final ChatMessageOrchestrator chatMessageOrchestrator;

    @PostMapping
    public ResponseEntity<ApiResponse<ChatMessageResponse>> sendMessage(
            @Auth AuthUser authUser,
            @PathVariable Long roomId,
            @Valid @RequestBody SendChatMessageRequest request) {
        ChatMessageResponse response = chatMessageOrchestrator.sendMessage(roomId, authUser.id(), request.text());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }
}
