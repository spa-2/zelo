package com.server.zelo.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HashUtilTest {

    // 알려진 SHA-256, SHA-512 값 ("abc"의 표준 테스트 벡터)
    private static final String SHA_256_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String SHA_512_ABC_PREFIX = "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a";

    @Test
    void SHA_256_해시를_소문자_16진수로_반환한다() {
        // given
        HashUtil hashUtil = new HashUtil("SHA-256");

        // when
        String hash = hashUtil.hash("abc");

        // then
        assertThat(hash).isEqualTo(SHA_256_ABC);
    }

    @Test
    void 같은_값은_항상_같은_해시이고_다른_값은_다른_해시다() {
        // given
        HashUtil hashUtil = new HashUtil("SHA-256");

        // when & then
        assertThat(hashUtil.hash("token")).isEqualTo(hashUtil.hash("token"));
        assertThat(hashUtil.hash("token")).isNotEqualTo(hashUtil.hash("token2"));
    }

    @Test
    void 해시에는_원문이_들어있지_않다() {
        // given
        HashUtil hashUtil = new HashUtil("SHA-256");

        // when
        String hash = hashUtil.hash("secret-refresh-token");

        // then
        assertThat(hash).doesNotContain("secret-refresh-token");
    }

    @Test
    void 알고리즘을_바꾸면_다른_해시가_나온다() {
        // when
        String sha256 = new HashUtil("SHA-256").hash("abc");
        String sha512 = new HashUtil("SHA-512").hash("abc");

        // then
        // 알고리즘을 바꾸면 이미 저장된 해시와 일치하지 않게 되므로 운영 중 변경하면 모든 사용자가 다시 로그인해야 함
        assertThat(sha512).hasSize(128).startsWith(SHA_512_ABC_PREFIX).isNotEqualTo(sha256);
    }

    @Test
    void 지원하지_않는_알고리즘이면_예외가_발생한다() {
        // given
        HashUtil hashUtil = new HashUtil("NO-SUCH-ALGORITHM");

        // when & then
        assertThatThrownBy(() -> hashUtil.hash("abc")).isInstanceOf(IllegalStateException.class);
    }

    // 설정 키를 @Value에 ${}로 감싸지 않으면 "zelo.hash.algorithm"이라는 글자 자체가 알고리즘 이름으로 들어가서 모든 해시가 실패함
    @Test
    void 설정_파일의_알고리즘_값이_주입된다() {
        new ApplicationContextRunner()
                .withUserConfiguration(HashUtil.class)
                .withPropertyValues("zelo.hash.algorithm=SHA-256")
                .run(context -> assertThat(context.getBean(HashUtil.class).hash("abc")).isEqualTo(SHA_256_ABC));
    }
}
