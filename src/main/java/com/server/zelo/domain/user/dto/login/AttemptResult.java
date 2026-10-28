package com.server.zelo.domain.user.dto.login;

public record AttemptResult(int count, long retryAfterSeconds) {
    public static AttemptResult of(Number count, Number retryAfterSeconds) {
        return new AttemptResult(count.intValue(), retryAfterSeconds.longValue());
    }
}
