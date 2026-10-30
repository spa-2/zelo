package com.server.zelo.domain.chat.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.RateLimitExceededException;
import com.server.zelo.common.monitoring.BusinessMetric;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.common.util.HashUtil;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.model.ChatIdempotencyRecord;
import com.server.zelo.domain.chat.repository.ChatIdempotencyRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 메시지 전송이 재전송으로 두 번 처리되지 않게 한다. Redis만 쓰므로 @Transactional을 붙이지 않는다
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatIdempotencyService {

    private final HashUtil hashUtil;
    private final BusinessMetrics businessMetrics;
    private final ChatIdempotencyRepository chatIdempotencyRepository;

    // 같은 사용자의 같은 키는 한 번만 처리한다. 처리 중에 다시 오면 409, 끝난 뒤 다시 오면 저장해 둔 결과로 첫 응답을 다시 만든다.
    // 사용자 메시지를 커밋한 뒤의 실패(FALLBACK 포함)는 결과로 남겨, 같은 키로 다시 보내도 메시지·방이 또 저장되지 않게 한다.
    // 커밋 전 실패(방 권한 오류 등)는 저장된 것이 없으므로 키를 지워 같은 키로 다시 처리할 수 있게 한다.
    // firstResponse: 끝난 기록(AI 메시지 ID가 있는)으로 첫 응답을 다시 만든다. 메시지 조회는 Command가 한다
    public <T> T runOnce(Long userId, String idempotencyKey, String request, Function<Runnable, T> work,
                         Function<T, Long> roomIdOf, Function<T, ChatMessageResponse> messageOf,
                         Function<ChatIdempotencyRecord, T> firstResponse) {
        validateIdempotencyKey(idempotencyKey);
        String requestHash = hashUtil.hash(request);
        if (!chatIdempotencyRepository.saveIfAbsent(userId, idempotencyKey, ChatIdempotencyRecord.processing(requestHash))) {
            return replay(userId, idempotencyKey, requestHash, firstResponse);
        }
        countIdempotency("FIRST");

        AtomicBoolean committed = new AtomicBoolean();
        T result;
        try {
            result = work.apply(() -> committed.set(true));
        } catch (RateLimitExceededException e) {
            // data에 FALLBACK 응답이 아니라 Retry-After 초가 들어 있어 아래 FALLBACK 처리로 보내지 않는다.
            // 저장 전에 거절됐으니 키를 지워, 시간이 지나면 같은 키로 다시 보낼 수 있게 한다
            endWithoutResponse(userId, idempotencyKey, requestHash, committed.get(), e.getErrorCode());
            throw e;
        } catch (BusinessException e) {
            if (e.getData() == null) {
                endWithoutResponse(userId, idempotencyKey, requestHash, committed.get(), e.getErrorCode());
                throw e;
            }
            // FALLBACK 예외의 data는 성공 응답과 같은 모양(T)으로 만든다(fallback의 toResponse)
            @SuppressWarnings("unchecked")
            T data = (T) e.getData();
            saveDone(userId, idempotencyKey,
                    ChatIdempotencyRecord.done(requestHash, roomIdOf.apply(data), messageOf.apply(data), e.getErrorCode()));
            throw e;
        } catch (RuntimeException e) {
            // 처리되지 않은 예외는 GlobalExceptionHandler가 500 INTERNAL_ERROR로 응답하므로 다시 올 때도 같은 코드로 준다
            endWithoutResponse(userId, idempotencyKey, requestHash, committed.get(), ErrorCode.INTERNAL_ERROR);
            throw e;
        }
        saveDone(userId, idempotencyKey,
                ChatIdempotencyRecord.done(requestHash, roomIdOf.apply(result), messageOf.apply(result), null));
        return result;
    }

    private <T> T replay(Long userId, String idempotencyKey, String requestHash,
                         Function<ChatIdempotencyRecord, T> firstResponse) {
        // 선점에 실패한 사이 TTL로 사라졌으면 처리 중이었던 것과 같게 보고 다시 보내게 한다
        Optional<ChatIdempotencyRecord> found = chatIdempotencyRepository.find(userId, idempotencyKey);
        if (found.isEmpty()) {
            countIdempotency("EXPIRED");
            throw new BusinessException(ErrorCode.TIMEOUT_EXCEPTION);
        }
        ChatIdempotencyRecord record = found.get();
        if (!record.requestHash().equals(requestHash)) {
            countIdempotency("KEY_REUSED");
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (!record.done()) {
            countIdempotency("IN_PROGRESS");
            throw new BusinessException(ErrorCode.TIMEOUT_EXCEPTION);
        }
        countIdempotency("REPLAY");
        if (record.messageId() == null) {
            throw new BusinessException(record.failure());
        }
        T response = firstResponse.apply(record);
        if (record.failure() != null) {
            throw new BusinessException(record.failure(), record.failure().getMessage(), response);
        }
        return response;
    }

    // 응답 메시지 없이 실패했을 때. 사용자 메시지를 이미 저장했으면 같은 오류로 끝난 것으로 남기고, 아니면 키를 지운다
    private void endWithoutResponse(Long userId, String idempotencyKey, String requestHash, boolean committed,
                                    ErrorCode failure) {
        if (committed) {
            saveDone(userId, idempotencyKey, ChatIdempotencyRecord.failed(requestHash, failure));
            return;
        }
        deleteQuietly(userId, idempotencyKey);
    }

    // 실패 처리 중이라 원래 예외를 그대로 던져야 한다. 삭제가 실패해도 처리 중 표시는 PROCESSING_TTL 뒤에 풀린다
    private void deleteQuietly(Long userId, String idempotencyKey) {
        try {
            chatIdempotencyRepository.delete(userId, idempotencyKey);
        } catch (RuntimeException e) {
            log.warn("메시지 전송 멱등 키 삭제 실패 userId={}", userId, e);
            businessMetrics.count(BusinessMetric.CHAT_IDEMPOTENCY_STORE_FAILURE, "operation", "DELETE");
        }
    }

    // 처리는 이미 끝났으니 결과 저장이 실패해도 응답은 그대로 보낸다. 처리 중 표시는 PROCESSING_TTL 뒤에 풀린다
    private void saveDone(Long userId, String idempotencyKey, ChatIdempotencyRecord record) {
        try {
            chatIdempotencyRepository.save(userId, idempotencyKey, record);
        } catch (RuntimeException e) {
            log.warn("메시지 전송 멱등 결과 저장 실패 userId={}", userId, e);
            businessMetrics.count(BusinessMetric.CHAT_IDEMPOTENCY_STORE_FAILURE, "operation", "SAVE");
        }
    }

    // 화면은 [보내기]마다 UUID를 만든다. 다른 모양은 잘못 만든 키라 거절한다
    private static void validateIdempotencyKey(String idempotencyKey) {
        try {
            UUID.fromString(idempotencyKey);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private void countIdempotency(String result) {
        businessMetrics.count(BusinessMetric.CHAT_IDEMPOTENCY, "result", result);
    }
}
