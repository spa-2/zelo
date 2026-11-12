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

    private static final long WAIT_TIME_SECONDS = 3;
    // leaseTime을 주면 watchdog이 꺼져서, 해제가 안 된 락도 이 시간 뒤에 저절로 사라진다. 없으면 watchdog이 락을 계속 연장해서 DB 장애(failover) 뒤에 락이 영구히 남았다
    private static final long LEASE_TIME_SECONDS = 60;

    private final RedissonClient redissonClient;

    public <T> T execute(String key, Supplier<T> supplier) {
        RLock lock = redissonClient.getLock(key);

        boolean acquired;
        try {
            acquired = lock.tryLock(WAIT_TIME_SECONDS, LEASE_TIME_SECONDS, TimeUnit.SECONDS);
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
