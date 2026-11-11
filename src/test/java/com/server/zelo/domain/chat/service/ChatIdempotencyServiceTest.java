package com.server.zelo.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.RateLimitExceededException;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.common.util.HashUtil;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.entity.ChatSender;
import com.server.zelo.domain.chat.model.ChatIdempotencyRecord;
import com.server.zelo.domain.chat.repository.ChatIdempotencyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

// 키 선점·재응답·삭제 규칙만 본다. 어떤 요청 문자열로 부르는지, 첫 응답을 어떻게 다시 만드는지는 ChatMessageCommandServiceTest에서 본다
@ExtendWith(MockitoExtension.class)
class ChatIdempotencyServiceTest {

    private static final String KEY = "7f3c2a9e-1b4d-4c8a-9e6f-2d5b8a1c3e70";
    private static final String REQUEST = "room:10:안녕";
    private static final ChatMessageResponse ANSWER =
            new ChatMessageResponse(42L, ChatSender.ASSISTANT, "답변", null, null, false, LocalDateTime.now());

    // 같은 문장은 같은 해시가 나와야 해서 진짜 객체를 쓴다
    @Spy
    private HashUtil hashUtil = new HashUtil("SHA-256");

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Spy
    private BusinessMetrics businessMetrics = new BusinessMetrics(meterRegistry);

    @Mock
    private ChatIdempotencyRepository chatIdempotencyRepository;

    @InjectMocks
    private ChatIdempotencyService chatIdempotencyService;

    @Test
    void 처음_온_키는_처리하고_응답한_AI_메시지를_결과로_저장한다() {
        // given
        given(chatIdempotencyRepository.saveIfAbsent(eq(1L), eq(KEY), any())).willReturn(true);

        // when
        ChatMessageResponse response = runOnce(REQUEST, onCommitted -> ANSWER);

        // then
        assertThat(response).isSameAs(ANSWER);
        then(chatIdempotencyRepository).should()
                .save(1L, KEY, ChatIdempotencyRecord.done(hashUtil.hash(REQUEST), 10L, ANSWER, null));
        assertThat(counter("zelo.chat.idempotency", "result", "FIRST")).isEqualTo(1);
    }

    @Test
    void 처리가_끝난_키가_다시_오면_저장한_결과로_첫_응답을_다시_만들고_다시_처리하지_않는다() {
        // given
        String hash = hashUtil.hash(REQUEST);
        ChatIdempotencyRecord done = new ChatIdempotencyRecord(true, hash, 10L, 42L, null, null);
        given(chatIdempotencyRepository.saveIfAbsent(eq(1L), eq(KEY), any())).willReturn(false);
        given(chatIdempotencyRepository.find(1L, KEY)).willReturn(Optional.of(done));
        AtomicBoolean worked = new AtomicBoolean();

        // when
        ChatMessageResponse response = chatIdempotencyService.runOnce(1L, KEY, REQUEST, onCommitted -> {
            worked.set(true);
            return ANSWER;
        }, answer -> 10L, Function.identity(), record -> {
            assertThat(record).isSameAs(done);
            return ANSWER;
        });

        // then
        assertThat(response).isSameAs(ANSWER);
        assertThat(worked).isFalse();
        assertThat(counter("zelo.chat.idempotency", "result", "REPLAY")).isEqualTo(1);
    }

    @Test
    void LLM이_실패해_고정_문구를_저장했으면_그_메시지와_실패를_결과로_저장한다() {
        // given: 답변 쪽은 고정 문구 메시지를 성공 응답과 같은 모양으로 예외의 data에 담는다
        given(chatIdempotencyRepository.saveIfAbsent(eq(1L), eq(KEY), any())).willReturn(true);
        BusinessException fallback = new BusinessException(ErrorCode.AI_TIMEOUT, ErrorCode.AI_TIMEOUT.getMessage(), ANSWER);

        // when & then
        assertThatThrownBy(() -> runOnce(REQUEST, onCommitted -> {
            onCommitted.run();
            throw fallback;
        })).isSameAs(fallback);
        then(chatIdempotencyRepository).should()
                .save(1L, KEY, ChatIdempotencyRecord.done(hashUtil.hash(REQUEST), 10L, ANSWER, ErrorCode.AI_TIMEOUT));
        then(chatIdempotencyRepository).should(never()).delete(any(), any());
    }

    @Test
    void 고정_문구로_끝난_키가_다시_오면_그때의_고정_문구를_data에_담아_같은_실패를_던진다() {
        // given
        String hash = hashUtil.hash(REQUEST);
        given(chatIdempotencyRepository.saveIfAbsent(eq(1L), eq(KEY), any())).willReturn(false);
        given(chatIdempotencyRepository.find(1L, KEY)).willReturn(Optional.of(
                new ChatIdempotencyRecord(true, hash, 10L, 42L, null, ErrorCode.AI_TIMEOUT)));

        // when & then
        assertThatThrownBy(() -> runOnce(REQUEST, onCommitted -> ANSWER))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_TIMEOUT);
                    assertThat(exception.getMessage()).isEqualTo(ErrorCode.AI_TIMEOUT.getMessage());
                    assertThat(exception.getData()).isSameAs(ANSWER);
                });
    }

    @Test
    void 횟수_제한으로_거절되면_키를_지워_시간이_지난_뒤_같은_키로_다시_보낼_수_있게_한다() {
        // given
        given(chatIdempotencyRepository.saveIfAbsent(eq(1L), eq(KEY), any())).willReturn(true);

        // when & then
        assertThatThrownBy(() -> runOnce(REQUEST, onCommitted -> {
            throw new RateLimitExceededException(ErrorCode.CHAT_RATE_LIMITED, 15L);
        })).isInstanceOf(RateLimitExceededException.class);
        then(chatIdempotencyRepository).should().delete(1L, KEY);
        then(chatIdempotencyRepository).should(never()).save(any(), any(), any());
    }

    @Test
    void 같은_키가_처리_중이면_409를_던지고_다시_처리하지_않는다() {
        // given
        String hash = hashUtil.hash(REQUEST);
        given(chatIdempotencyRepository.saveIfAbsent(eq(1L), eq(KEY), any())).willReturn(false);
        given(chatIdempotencyRepository.find(1L, KEY)).willReturn(Optional.of(ChatIdempotencyRecord.processing(hash)));
        AtomicBoolean worked = new AtomicBoolean();

        // when & then
        assertThatThrownBy(() -> runOnce(REQUEST, onCommitted -> {
            worked.set(true);
            return ANSWER;
        })).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TIMEOUT_EXCEPTION));
        assertThat(worked).isFalse();
        assertThat(counter("zelo.chat.idempotency", "result", "IN_PROGRESS")).isEqualTo(1);
    }

    @Test
    void 선점에_실패한_사이_키가_사라졌으면_409를_던진다() {
        // given
        given(chatIdempotencyRepository.saveIfAbsent(eq(1L), eq(KEY), any())).willReturn(false);
        given(chatIdempotencyRepository.find(1L, KEY)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> runOnce(REQUEST, onCommitted -> ANSWER))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TIMEOUT_EXCEPTION));
        assertThat(counter("zelo.chat.idempotency", "result", "EXPIRED")).isEqualTo(1);
    }

    @Test
    void 같은_키로_다른_문장이_오면_409로_거절한다() {
        // given
        String hash = hashUtil.hash("room:10:계란 2개 더");
        given(chatIdempotencyRepository.saveIfAbsent(eq(1L), eq(KEY), any())).willReturn(false);
        given(chatIdempotencyRepository.find(1L, KEY)).willReturn(Optional.of(
                new ChatIdempotencyRecord(true, hash, 10L, 42L, null, null)));

        // when & then
        assertThatThrownBy(() -> runOnce("room:10:대파 빼줘", onCommitted -> ANSWER))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void 응답을_저장하지_못한_실패면_키를_지워_같은_키로_다시_처리할_수_있게_한다() {
        // given: 방 권한 오류처럼 사용자 메시지를 저장하기 전의 실패
        given(chatIdempotencyRepository.saveIfAbsent(eq(1L), eq(KEY), any())).willReturn(true);

        // when & then
        assertThatThrownBy(() -> runOnce(REQUEST, onCommitted -> {
            throw new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED);
        })).isInstanceOf(BusinessException.class);
        then(chatIdempotencyRepository).should().delete(1L, KEY);
        then(chatIdempotencyRepository).should(never()).save(any(), any(), any());
    }

    @Test
    void 사용자_메시지를_저장하기_전에_예상하지_못한_오류가_나면_키를_지운다() {
        // given
        given(chatIdempotencyRepository.saveIfAbsent(eq(1L), eq(KEY), any())).willReturn(true);

        // when & then
        assertThatThrownBy(() -> runOnce(REQUEST, onCommitted -> {
            throw new IllegalStateException("redis down");
        })).isInstanceOf(IllegalStateException.class);
        then(chatIdempotencyRepository).should().delete(1L, KEY);
    }

    @Test
    void 키_삭제가_실패해도_원래_예외를_던진다() {
        // given: 커밋 전 실패로 키를 지우려는데 Redis도 실패한다
        given(chatIdempotencyRepository.saveIfAbsent(eq(1L), eq(KEY), any())).willReturn(true);
        willThrow(new IllegalStateException("redis down")).given(chatIdempotencyRepository).delete(1L, KEY);

        // when & then
        assertThatThrownBy(() -> runOnce(REQUEST, onCommitted -> {
            throw new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED);
        })).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_ACCESS_DENIED));
        assertThat(counter("zelo.chat.idempotency.store.failure", "operation", "DELETE")).isEqualTo(1);
    }

    @Test
    void 사용자_메시지를_저장한_뒤_응답_없이_실패하면_키를_지우지_않고_그_오류로_끝난_것으로_저장한다() {
        // given
        given(chatIdempotencyRepository.saveIfAbsent(eq(1L), eq(KEY), any())).willReturn(true);

        // when & then
        assertThatThrownBy(() -> runOnce(REQUEST, onCommitted -> {
            onCommitted.run();
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        })).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        then(chatIdempotencyRepository).should()
                .save(1L, KEY, ChatIdempotencyRecord.failed(hashUtil.hash(REQUEST), ErrorCode.SERVICE_UNAVAILABLE));
        then(chatIdempotencyRepository).should(never()).delete(any(), any());
    }

    @Test
    void 사용자_메시지를_저장한_뒤_예상하지_못한_오류가_나면_서버_오류로_끝난_것으로_저장한다() {
        // given
        given(chatIdempotencyRepository.saveIfAbsent(eq(1L), eq(KEY), any())).willReturn(true);

        // when & then
        assertThatThrownBy(() -> runOnce(REQUEST, onCommitted -> {
            onCommitted.run();
            throw new IllegalStateException("db down");
        })).isInstanceOf(IllegalStateException.class);
        then(chatIdempotencyRepository).should()
                .save(1L, KEY, ChatIdempotencyRecord.failed(hashUtil.hash(REQUEST), ErrorCode.INTERNAL_ERROR));
        then(chatIdempotencyRepository).should(never()).delete(any(), any());
    }

    @Test
    void 응답_없이_실패로_끝난_키가_다시_오면_같은_오류를_던지고_다시_처리하지_않는다() {
        // given
        String hash = hashUtil.hash(REQUEST);
        given(chatIdempotencyRepository.saveIfAbsent(eq(1L), eq(KEY), any())).willReturn(false);
        given(chatIdempotencyRepository.find(1L, KEY))
                .willReturn(Optional.of(ChatIdempotencyRecord.failed(hash, ErrorCode.SERVICE_UNAVAILABLE)));
        AtomicBoolean rebuilt = new AtomicBoolean();

        // when & then
        assertThatThrownBy(() -> chatIdempotencyService.runOnce(1L, KEY, REQUEST, onCommitted -> ANSWER,
                response -> 10L, Function.identity(), record -> {
                    rebuilt.set(true);
                    return ANSWER;
                }))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(exception.getData()).isNull();
                });
        assertThat(rebuilt).isFalse();
    }

    @Test
    void 결과_저장이_실패해도_처리한_응답은_돌려준다() {
        // given
        given(chatIdempotencyRepository.saveIfAbsent(eq(1L), eq(KEY), any())).willReturn(true);
        willThrow(new IllegalStateException("redis down")).given(chatIdempotencyRepository).save(eq(1L), eq(KEY), any());

        // when
        ChatMessageResponse response = runOnce(REQUEST, onCommitted -> ANSWER);

        // then
        assertThat(response.messageId()).isEqualTo(42L);
        assertThat(counter("zelo.chat.idempotency.store.failure", "operation", "SAVE")).isEqualTo(1);
    }

    @Test
    void UUID가_아닌_키는_400으로_거절하고_저장하지_않는다() {
        // when & then
        assertThatThrownBy(() -> chatIdempotencyService.runOnce(1L, "not-a-uuid", REQUEST, onCommitted -> ANSWER,
                response -> 10L, Function.identity(), record -> ANSWER))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        then(chatIdempotencyRepository).shouldHaveNoInteractions();
    }

    private ChatMessageResponse runOnce(String request, Function<Runnable, ChatMessageResponse> work) {
        return chatIdempotencyService.runOnce(1L, KEY, request, work,
                response -> 10L, Function.identity(), record -> ANSWER);
    }

    private double counter(String name, String... tags) {
        return meterRegistry.get(name).tags(tags).counter().count();
    }
}
