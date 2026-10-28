package com.server.zelo.domain.chat.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.common.auth.Auth;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.domain.chat.dto.request.ChooseSubstituteRequest;
import com.server.zelo.domain.chat.dto.request.SearchChatMessageCondition;
import com.server.zelo.domain.chat.dto.request.SendChatMessageRequest;
import com.server.zelo.domain.chat.dto.request.UpdateQuoteItemRequest;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.response.GetChatMessagesResponse;
import com.server.zelo.domain.chat.dto.response.StartChatResponse;
import com.server.zelo.domain.chat.service.ChatMessageCommandService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat")
public class ChatMessageController {

    // 화면이 [보내기]마다 만드는 UUID. 응답을 못 받아 다시 보낼 때는 같은 값을 보내 두 번 처리되지 않게 한다
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final ChatMessageCommandService chatMessageCommandService;

    // 채팅방 없이 메인 화면에서 첫 메시지를 보내면 방 생성 + 메세지 생성
    @PostMapping("/messages")
    public ResponseEntity<ApiResponse<StartChatResponse>> startChat(
            @Auth AuthUser authUser,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody SendChatMessageRequest request) {
        StartChatResponse response = chatMessageCommandService.startChat(authUser.id(), request.text(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<ApiResponse<GetChatMessagesResponse>> getMessages(
            @Auth AuthUser authUser,
            @PathVariable Long roomId,
            @Valid @ModelAttribute SearchChatMessageCondition condition) {
        GetChatMessagesResponse response = chatMessageCommandService.getMessages(roomId, authUser.id(), condition);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/rooms/{roomId}/messages")
    public ResponseEntity<ApiResponse<ChatMessageResponse>> sendMessage(
            @Auth AuthUser authUser,
            @PathVariable Long roomId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody SendChatMessageRequest request) {
        ChatMessageResponse response = chatMessageCommandService.sendMessage(roomId, authUser.id(), request.text(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    // FALLBACK 메시지의 [다시 시도]. 바로 앞 사용자 메시지로 답변을 다시 만들어 새 AI 메시지로 돌려준다
    @PostMapping("/rooms/{roomId}/messages/{messageId}/retry")
    public ResponseEntity<ApiResponse<ChatMessageResponse>> retryMessage(
            @Auth AuthUser authUser,
            @PathVariable Long roomId,
            @PathVariable Long messageId) {
        ChatMessageResponse response = chatMessageCommandService.retryMessage(roomId, authUser.id(), messageId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    // 견적 카드의 ✕, −/+ 버튼. 결과를 AI 메시지(새 카드 또는 안내 문장)로 저장해 돌려준다
    @PostMapping("/rooms/{roomId}/quote-items")
    public ResponseEntity<ApiResponse<ChatMessageResponse>> updateQuoteItem(
            @Auth AuthUser authUser,
            @PathVariable Long roomId,
            @Valid @RequestBody UpdateQuoteItemRequest request) {
        ChatMessageResponse response = chatMessageCommandService.updateQuoteItem(roomId, authUser.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    // 견적 카드 대체 후보의 [좋아요]·[빼기]. 결과를 AI 메시지(새 카드 또는 안내 문장)로 저장해 돌려준다
    @PostMapping("/rooms/{roomId}/quote-substitutes")
    public ResponseEntity<ApiResponse<ChatMessageResponse>> chooseSubstitute(
            @Auth AuthUser authUser,
            @PathVariable Long roomId,
            @Valid @RequestBody ChooseSubstituteRequest request) {
        ChatMessageResponse response = chatMessageCommandService.chooseSubstitute(roomId, authUser.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }
}
