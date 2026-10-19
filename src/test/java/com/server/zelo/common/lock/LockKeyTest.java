package com.server.zelo.common.lock;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LockKeyTest {

    @Test
    void 로그인_락_키는_이메일_단위로_만들어진다() {
        // when
        String key = LockKey.login("jieun@example.com");

        // then
        assertThat(key).isEqualTo("lock:user:login:jieun@example.com");
    }

    @Test
    void 이메일이_다르면_로그인_락_키도_다르다() {
        // when & then
        assertThat(LockKey.login("a@example.com")).isNotEqualTo(LockKey.login("b@example.com"));
    }

    @Test
    void 가입과_로그인은_같은_이메일이어도_락_키가_다르다() {
        // when & then
        assertThat(LockKey.login("a@example.com")).isNotEqualTo(LockKey.createUser("a@example.com"));
    }
}
