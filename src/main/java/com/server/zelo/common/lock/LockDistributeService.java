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

        boolean acquired;
        try {
            acquired = lock.tryLock(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.TIMEOUT_EXCEPTION);
        }

        if (!acquired) {
            throw new BusinessException(ErrorCode.TIMEOUT_EXCEPTION);
        }

        try {
            return supplier.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
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
