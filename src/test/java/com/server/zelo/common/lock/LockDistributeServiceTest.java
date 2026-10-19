package com.server.zelo.common.lock;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class LockDistributeServiceTest {

    private static final String KEY = "lock:test";

    @Mock
    private RLock lock;

    @Mock
    private RedissonClient redissonClient;

    @InjectMocks
    private LockDistributeService lockDistributeService;

    @Test
    void 락을_얻으면_로직을_실행하고_결과를_반환한다() throws InterruptedException {
        // given
        given(redissonClient.getLock(KEY)).willReturn(lock);
        given(lock.tryLock(3, TimeUnit.SECONDS)).willReturn(true);

        // when
        String result = lockDistributeService.execute(KEY, () -> "result");

        // then
        assertThat(result).isEqualTo("result");
        then(lock).should().unlock();
    }

    @Test
    void 반환값이_없는_로직도_락_안에서_실행한다() throws InterruptedException {
        // given
        given(redissonClient.getLock(KEY)).willReturn(lock);
        given(lock.tryLock(3, TimeUnit.SECONDS)).willReturn(true);
        AtomicBoolean executed = new AtomicBoolean(false);

        // when
        lockDistributeService.execute(KEY, () -> executed.set(true));

        // then
        assertThat(executed).isTrue();
        then(lock).should().unlock();
    }

    @Test
    void 락을_얻지_못하면_예외가_발생하고_로직은_실행하지_않는다() throws InterruptedException {
        // given
        given(redissonClient.getLock(KEY)).willReturn(lock);
        given(lock.tryLock(3, TimeUnit.SECONDS)).willReturn(false);
        AtomicBoolean executed = new AtomicBoolean(false);

        // when & then
        assertThatThrownBy(() -> lockDistributeService.execute(KEY, () -> executed.set(true)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TIMEOUT_EXCEPTION);
        assertThat(executed).isFalse();
        // 락을 얻지 못했으므로 해제하면 안 됨
        then(lock).should(never()).unlock();
    }

    @Test
    void 로직에서_예외가_발생해도_락을_해제한다() throws InterruptedException {
        // given
        given(redissonClient.getLock(KEY)).willReturn(lock);
        given(lock.tryLock(3, TimeUnit.SECONDS)).willReturn(true);
        Supplier<String> failing = () -> {
            throw new IllegalStateException("fail");
        };

        // when & then
        assertThatThrownBy(() -> lockDistributeService.execute(KEY, failing))
                .isInstanceOf(IllegalStateException.class);
        then(lock).should().unlock();
    }

    @Test
    void 락_대기_중_인터럽트되면_예외가_발생하고_인터럽트_상태를_유지한다() throws InterruptedException {
        // given
        given(redissonClient.getLock(KEY)).willReturn(lock);
        given(lock.tryLock(3, TimeUnit.SECONDS)).willThrow(new InterruptedException());

        try {
            // when & then
            assertThatThrownBy(() -> lockDistributeService.execute(KEY, () -> "result"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.TIMEOUT_EXCEPTION);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            then(lock).should(never()).unlock();
        } finally {
            // 다른 테스트에 인터럽트 상태가 남지 않게 비움
            Thread.interrupted();
        }
    }
}
