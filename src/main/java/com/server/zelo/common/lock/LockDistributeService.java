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

        acquire(lock);

        // 락을 얻은 뒤에만 try에 들어오므로 finally에서는 해제하는 락은 무조건 내거임
        try {
            return supplier.get();
        } finally {
            lock.unlock();
        }
    }

    public void execute(String key, Runnable runnable) {
        execute(key, () -> {
            runnable.run();
            return null;
        });
    }

    private void acquire(RLock lock) {
        try {
            // 3초 안에 lock을 얻으면 true, 못 얻으면 예외
            if (!lock.tryLock(3, TimeUnit.SECONDS)) {
                throw new BusinessException(ErrorCode.TIMEOUT_EXCEPTION);
            }
        } catch (InterruptedException e) {
            // 락 대기 중 스레드가 중단됨
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.TIMEOUT_EXCEPTION);
        }
    }
}
