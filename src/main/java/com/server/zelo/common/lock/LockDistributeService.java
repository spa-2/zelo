package com.server.zelo.common.lock;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class LockDistributeService {

    private final RedissonClient redissonClient;

    public <T> T execute(String key, Supplier<T> supplier) {
        RLock lock = redissonClient.getLock(key);

        boolean acquired = false;

        try {
            // 3초 안에 lock을 얻으면 true
            acquired = lock.tryLock(3, TimeUnit.SECONDS);

            // 3초 동안 lock을 얻지 못하면 예외
            if (!acquired) {
                throw new BusinessException(ErrorCode.TIMEOUT_EXCEPTION);
            }

            // 로직 수행
            return supplier.get();

        } catch (InterruptedException e) {
            // 락 대기 중 스레드가 중단됨
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.TIMEOUT_EXCEPTION);

        } finally {
            // 락을 획득했고 현재 스레드가 보유 중일 때만 해제
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public void execute(String key, Runnable runnable) {
        execute(key, () -> {
            runnable.run();
            return null;
        });
    }
}