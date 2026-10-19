package com.server.zelo.domain.chat.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.common.auth.Auth;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.domain.chat.dto.CreateChatRoomResponse;
import com.server.zelo.domain.chat.service.ChatRoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat/rooms")
public class ChatRoomController {

    private final ChatRoomService chatRoomService;

    @PostMapping
    public ResponseEntity<ApiResponse<CreateChatRoomResponse>> createChatRoom(@Auth AuthUser authUser) {
        CreateChatRoomResponse response = chatRoomService.createChatRoom(authUser.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }
}
